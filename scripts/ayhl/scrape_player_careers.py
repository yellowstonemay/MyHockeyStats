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
import os
import sys
import time
import re
from datetime import datetime
from playwright.sync_api import sync_playwright

THIS_DIR = os.path.dirname(__file__)
DATA_DIR = os.path.join(THIS_DIR, 'data')
PAGES_DIR = os.path.join(DATA_DIR, 'player_pages')
if not os.path.exists(PAGES_DIR):
    os.makedirs(PAGES_DIR, exist_ok=True)


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

    # aggregated output file
    ts = datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')
    out_all = os.path.join(DATA_DIR, f'ayhl-player-career-{ts}.csv')
    # remove if exists from previous failed run
    if os.path.exists(out_all):
        os.remove(out_all)
    header_written = False

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        for i, entry in enumerate(playerids, start=1):
            pid = entry['playerid']
            provided_name = entry.get('player_name', '')
            try:
                url = f'https://atlantichockey.org/playerpage.php?playerid={pid}'
                print(f'[{i}/{len(playerids)}] Fetching player {pid} -> {url}')
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
                    # still write metadata row placeholder if desired
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

                # write to aggregated CSV
                with open(out_all, 'a', newline='', encoding='utf-8') as ofh:
                    w = csv.writer(ofh)
                    if not header_written:
                        out_header = ['playerid', 'player_name', 'birthday', 'hometown', 'position', 'shoots'] + headers
                        w.writerow(out_header)
                        header_written = True
                    for dr in data_rows:
                        row = [pid, player_name, birthday, hometown, position, shoots] + dr
                        w.writerow(row)

                print(f'   wrote {len(data_rows)} career rows for {pid} ({player_name})')
                time.sleep(0.15)
            except Exception as e:
                print(f'   ERROR fetching {pid}:', e)

        browser.close()

    print('Done. Aggregated career CSV:', out_all)


if __name__ == '__main__':
    main()
