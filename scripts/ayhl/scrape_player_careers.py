"""Scrape player pages (career statistics) for a list of playerids.

Input: one of:
 - path to CSV with header 'playerid' (default will pick the latest generated file in data/)
 - path to plain text file with one playerid per line

Outputs:
 - scripts/ayhl/data/player_pages/player_{playerid}.html (raw HTML)
 - scripts/ayhl/data/player_pages/player_{playerid}_table_{n}.csv for any parsed tables

Usage:
    python scrape_player_careers.py [input_file]

Note: Playwright must be installed and `python -m playwright install chromium` run.
"""
import csv
import glob
import json
import os
import sys
import time
import re
from playwright.sync_api import sync_playwright

THIS_DIR = os.path.dirname(__file__)
DATA_DIR = os.path.join(THIS_DIR, 'data')
PAGES_DIR = os.path.join(DATA_DIR, 'player_pages')
if not os.path.exists(PAGES_DIR):
    os.makedirs(PAGES_DIR, exist_ok=True)


def is_transient_navigation_error(message):
    m = (message or '').upper()
    transient_markers = [
        'ERR_NAME_NOT_RESOLVED',
        'ERR_INTERNET_DISCONNECTED',
        'ERR_CONNECTION_RESET',
        'ERR_CONNECTION_CLOSED',
        'ERR_CONNECTION_ABORTED',
        'ERR_NETWORK_CHANGED',
        'TIMED OUT',
        'TIMEOUT',
    ]
    return any(marker in m for marker in transient_markers)


def read_playerids_from_csv(path):
    pids = []
    with open(path, 'r', encoding='utf-8') as fh:
        r = csv.DictReader(fh)
        for row in r:
            pid = (row.get('playerid') or '').strip()
            name = (row.get('player_name') or row.get('player') or '').strip()
            if pid:
                pids.append({'playerid': pid, 'player_name': name})
    return pids


def read_playerids_from_plain(path):
    pids = []
    with open(path, 'r', encoding='utf-8') as fh:
        for line in fh:
            s = line.strip()
            if s:
                pids.append({'playerid': s, 'player_name': ''})
    return pids


def find_latest_playerid_file():
    # Prefer the stable filename if present
    stable = os.path.join(DATA_DIR, 'ayhl-player-id.csv')
    if os.path.exists(stable):
        return stable
    files = sorted(glob.glob(os.path.join(DATA_DIR, 'ayhl-player-id-*.csv')))
    return files[-1] if files else None


def parse_and_save_tables(html, out_prefix):
    # basic heuristic: look for <table> blocks and extract rows by splitting on <tr>
    # This is a best-effort fallback; saving raw HTML is primary.
    import re
    tables = re.findall(r'<table.*?>.*?</table>', html, flags=re.S|re.I)
    saved = 0
    for idx, tbl in enumerate(tables, start=1):
        # strip tags for simple CSV extraction
        rows = re.findall(r'<tr.*?>(.*?)</tr>', tbl, flags=re.S|re.I)
        if not rows:
            continue
        out_rows = []
        for tr in rows:
            # find all <td> or <th>
            cols = re.findall(r'<t[dh].*?>(.*?)</t[dh]>', tr, flags=re.S|re.I)
            # remove any tags inside cells
            clean = [re.sub(r'<.*?>', '', c).strip().replace('\n', ' ').replace('\r', '') for c in cols]
            out_rows.append(clean)
        if out_rows:
            out_csv = f"{out_prefix}_table_{idx}.csv"
            with open(out_csv, 'w', newline='', encoding='utf-8') as ofh:
                w = csv.writer(ofh)
                for r in out_rows:
                    w.writerow(r)
            saved += 1
    return saved


def load_completed_ids_from_career_csv(career_csv_path):
    """Infer completed player ids from the aggregated career CSV."""
    completed = set()
    if not os.path.exists(career_csv_path):
        return completed

    try:
        with open(career_csv_path, 'r', encoding='utf-8', newline='') as fh:
            reader = csv.DictReader(fh)
            if not reader.fieldnames or 'playerid' not in reader.fieldnames:
                return completed
            for row in reader:
                pid = (row.get('playerid') or '').strip()
                if pid:
                    completed.add(pid)
    except Exception:
        return set()

    return completed


def load_career_json(career_json_path):
    if not os.path.exists(career_json_path):
        return {}
    try:
        with open(career_json_path, 'r', encoding='utf-8') as fh:
            payload = json.load(fh)
            if isinstance(payload, dict):
                return payload
    except Exception:
        return {}
    return {}


def normalize_progress(raw_progress, ordered_input_ids):
    """Return simple checkpoint format:

    {
      "current_player_id": <str|null>,
      "completed_player_ids": [..],
            "empty_carrer_ids": [..],
            "need_retry_ids": [..]
    }

    Supports migration from older per-player status format.
    """
    ordered_set = set(ordered_input_ids)

    default = {
        'current_player_id': None,
        'completed_player_ids': [],
        'empty_carrer_ids': [],
        'need_retry_ids': [],
    }

    if not isinstance(raw_progress, dict):
        return default

    # New format
    if 'completed_player_ids' in raw_progress or 'empty_carrer_ids' in raw_progress or 'failed_player_ids' in raw_progress or 'need_retry_ids' in raw_progress:
        completed = [pid for pid in raw_progress.get('completed_player_ids', []) if pid in ordered_set]
        # Backward-compatible read from old key 'failed_player_ids'
        empty_carrer = [pid for pid in raw_progress.get('empty_carrer_ids', raw_progress.get('failed_player_ids', [])) if pid in ordered_set]
        need_retry = [pid for pid in raw_progress.get('need_retry_ids', []) if pid in ordered_set]
        current = raw_progress.get('current_player_id')
        if current not in ordered_set:
            current = None
        return {
            'current_player_id': current,
            'completed_player_ids': completed,
            'empty_carrer_ids': empty_carrer,
            'need_retry_ids': need_retry,
        }

    # Old format migration: {"<pid>": {"status": "success|failed|in_progress"...}}
    completed_set = set()
    empty_carrer_set = set()
    current = None
    for pid, meta in raw_progress.items():
        if pid not in ordered_set or not isinstance(meta, dict):
            continue
        status = meta.get('status')
        if status == 'success':
            completed_set.add(pid)
        elif status == 'failed':
            empty_carrer_set.add(pid)
        elif status == 'in_progress' and current is None:
            current = pid

    completed = [pid for pid in ordered_input_ids if pid in completed_set]
    empty_carrer = [pid for pid in ordered_input_ids if pid in empty_carrer_set]
    if current in completed_set or current in empty_carrer_set:
        current = None

    return {
        'current_player_id': current,
        'completed_player_ids': completed,
        'empty_carrer_ids': empty_carrer,
        'need_retry_ids': [],
    }


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    if arg:
        if arg.lower().endswith('.csv'):
            playerids = read_playerids_from_csv(arg)
        else:
            playerids = read_playerids_from_plain(arg)
    else:
        latest = find_latest_playerid_file()
        if not latest:
            print('No playerid CSV found. Run generate_player_ids.py first or supply an input file.')
            return
        print('No input provided: using latest:', latest)
        playerids = read_playerids_from_csv(latest)

    if not playerids:
        print('No playerids to process.')
        return

    print(f'Processing {len(playerids)} playerids...')

    # aggregated output file (stable json keyed by playerid)
    out_all = os.path.join(DATA_DIR, 'ayhl-player-career.json')
    # legacy csv path kept only for backward-compatible completed-id inference
    legacy_csv = os.path.join(DATA_DIR, 'ayhl-player-career.csv')
    # progress file to track per-player status so runs can be resumed
    progress_file = os.path.join(DATA_DIR, 'ayhl-player-career-progress.json')
    # create data dir if missing
    if not os.path.exists(DATA_DIR):
        os.makedirs(DATA_DIR, exist_ok=True)

    # load and normalize progress to simple player-id checkpoint model
    raw_progress = {}
    if os.path.exists(progress_file):
        try:
            with open(progress_file, 'r', encoding='utf-8') as pf:
                raw_progress = json.load(pf)
        except Exception:
            raw_progress = {}

    ordered_input_ids = [entry['playerid'] for entry in playerids]
    name_by_pid = {entry['playerid']: entry.get('player_name', '') for entry in playerids}
    progress = normalize_progress(raw_progress, ordered_input_ids)

    completed_set = set(progress.get('completed_player_ids', []))
    empty_carrer_set = set(progress.get('empty_carrer_ids', []))
    need_retry_set = set(progress.get('need_retry_ids', []))

    # Load existing JSON output and infer completed ids from it.
    career_by_player = load_career_json(out_all)
    completed_set |= set(career_by_player.keys())
    # Backward-compatible inference from legacy CSV if it exists.
    completed_set |= load_completed_ids_from_career_csv(legacy_csv)

    def save_career_json():
        try:
            with open(out_all, 'w', encoding='utf-8') as jf:
                json.dump(career_by_player, jf, indent=2, ensure_ascii=False)
        except Exception:
            pass

    # Save helper for simplified progress format
    def save_progress(current_player_id=None):
        payload = {
            'current_player_id': current_player_id,
            'completed_player_ids': [pid for pid in ordered_input_ids if pid in completed_set],
            'empty_carrer_ids': [pid for pid in ordered_input_ids if pid in empty_carrer_set],
            'need_retry_ids': [pid for pid in ordered_input_ids if pid in need_retry_set],
        }
        try:
            with open(progress_file, 'w', encoding='utf-8') as pf:
                json.dump(payload, pf, indent=2, ensure_ascii=False)
        except Exception:
            pass

    # Persist normalized/migrated progress immediately
    save_progress(progress.get('current_player_id'))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        # Process ids not completed and not in empty_carrer_ids.
        # ids in need_retry_ids are included so reruns can retry them.
        ordered_pids = [pid for pid in ordered_input_ids if pid not in completed_set and pid not in empty_carrer_set]
        if not ordered_pids:
            print('No playerids to process (all already succeeded).')
            browser.close()
            return

        # Summary for resume clarity
        total_players = len(ordered_input_ids)
        succeeded = len(completed_set)
        pending = len(ordered_pids)
        empty_carrer = len(empty_carrer_set)
        need_retry = len(need_retry_set)
        print(f"Resuming scrape: total={total_players}, succeeded={succeeded}, pending={pending}, empty_carrer={empty_carrer}, need_retry={need_retry}")
        last_current = progress.get('current_player_id')
        next_pending = ordered_pids[0] if ordered_pids else None
        print(f"Resume checkpoint current_player_id: {last_current if last_current else 'none'}")
        print(f"Resuming from next pending player_id: {next_pending if next_pending else 'none'}")
        total_to_process = len(ordered_pids)

        for i, pid in enumerate(ordered_pids, start=1):
            provided_name = name_by_pid.get(pid, '')
            # mark current player being processed and persist immediately
            save_progress(current_player_id=pid)
            try:
                url = f'https://atlantichockey.org/playerpage.php?playerid={pid}'
                print(f'[{i}/{total_to_process}] Fetching player {pid} -> {url}')
                page.goto(url, timeout=20000)
                html = page.content()

                # extract player metadata from page
                # Birthday, Hometown, Position, Shoots
                def extract_label(label):
                    m = re.search(rf'<strong>\s*{label}:\s*</strong>\s*([^<\n<]+)', html, flags=re.I)
                    if m:
                        return m.group(1).strip()
                    return ''

                birthday = extract_label('Birthday') or extract_label('Birthdate')
                hometown = extract_label('Hometown')
                position = ''
                shoots = ''
                # Position and Shoots may appear as 'Position:</strong> \n\t	Forward&nbsp;\n\t\t<strong>Shoots'
                pos_match = re.search(r'<strong>\s*Position:\s*</strong>\s*([^<]+)', html, flags=re.I)
                if pos_match:
                    position = pos_match.group(1).strip().replace('&nbsp;', ' ')
                shoots_match = re.search(r'<strong>\s*Shoots:\s*</strong>\s*([^<]+)', html, flags=re.I)
                if shoots_match:
                    shoots = shoots_match.group(1).strip()

                # player name from input CSV if provided; otherwise pick the <h1>
                # that contains '#<number>' (player header). If none, use the last <h1>.
                player_name = provided_name or ''
                if not player_name:
                    h1s = re.findall(r'<h1[^>]*>(.*?)</h1>', html, flags=re.S|re.I)
                    chosen = ''
                    for h in h1s:
                        if re.search(r'#\s*\d+', h):
                            # remove leading '#<num>' if present
                            chosen = re.sub(r'#\s*\d+\s*', '', h).strip()
                            break
                    if not chosen and h1s:
                        chosen = h1s[-1].strip()
                    # strip any remaining tags
                    player_name = re.sub(r'<.*?>', '', chosen).strip()

                # find career table: choose first table that contains 'Season' header
                tables = re.findall(r'<table.*?>.*?</table>', html, flags=re.S|re.I)
                career_table = None
                for tbl in tables:
                    if re.search(r'>\s*Season\s*<', tbl, flags=re.I):
                        career_table = tbl
                        break

                if not career_table:
                    print(f'   No career table found for {pid} ({player_name})')
                    empty_carrer_set.add(pid)
                    # Keep need_retry_ids persistent as an audit list.
                    save_progress(current_player_id=None)
                    time.sleep(0.15)
                    continue

                # parse header columns: find first <tr> that contains <th>
                tr_blocks = re.findall(r'(<tr.*?>.*?</tr>)', career_table, flags=re.S|re.I)
                headers = []
                header_tr_idx = None
                for idx_tr, tr_block in enumerate(tr_blocks):
                    if re.search(r'<th', tr_block, flags=re.I):
                        ths = re.findall(r'<th[^>]*>(.*?)</th>', tr_block, flags=re.S|re.I)
                        headers = [re.sub(r'<.*?>', '', t).strip() for t in ths]
                        header_tr_idx = idx_tr
                        break

                # if no <th>, try first tr as header using td/th tags
                if not headers and tr_blocks:
                    first_row = tr_blocks[0]
                    cells = re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', first_row, flags=re.S|re.I)
                    headers = [re.sub(r'<.*?>', '', c).strip() for c in cells]
                    header_tr_idx = 0

                # extract data rows after header_tr_idx
                data_rows = []
                for idx_tr, tr_block in enumerate(tr_blocks):
                    if header_tr_idx is not None and idx_tr == header_tr_idx:
                        continue
                    # extract <td> first
                    tds = re.findall(r'<td[^>]*>(.*?)</td>', tr_block, flags=re.S|re.I)
                    if not tds:
                        # fallback to any tdh
                        tds = re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', tr_block, flags=re.S|re.I)
                    if not tds:
                        continue
                    clean = [re.sub(r'<.*?>', '', td).strip() for td in tds]
                    data_rows.append(clean)

                # write/update aggregated JSON keyed by playerid
                career_rows = []
                for dr in data_rows:
                    row_obj = {}
                    for idx_col, col_name in enumerate(headers):
                        row_obj[col_name] = dr[idx_col] if idx_col < len(dr) else ''
                    career_rows.append(row_obj)

                career_by_player[pid] = {
                    'player_name': player_name,
                    'birthday': birthday,
                    'hometown': hometown,
                    'position': position,
                    'shoots': shoots,
                    'career': career_rows,
                }
                save_career_json()

                print(f'   wrote {len(data_rows)} career rows for {pid} ({player_name})')
                completed_set.add(pid)
                empty_carrer_set.discard(pid)
                # Keep need_retry_ids persistent as an audit list.
                save_progress(current_player_id=None)
                time.sleep(0.15)
            except Exception as e:
                print(f'   ERROR fetching {pid}:', e)
                err_msg = str(e)
                if is_transient_navigation_error(err_msg):
                    # Keep transient DNS/network failures in retry list.
                    need_retry_set.add(pid)
                    save_progress(current_player_id=None)
                    continue

                empty_carrer_set.add(pid)
                # Keep need_retry_ids persistent as an audit list.
                save_progress(current_player_id=None)

        browser.close()

    print('Done. Aggregated career JSON:', out_all)


if __name__ == '__main__':
    main()
