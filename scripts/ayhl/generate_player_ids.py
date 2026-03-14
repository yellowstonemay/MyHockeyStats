"""Generate a unique AYHL playerid list from all '*-ayhl-rosters.csv' files.

Writes output to: scripts/ayhl/data/ayhl-player-id-{timestamp}.csv
Columns: playerid, first_seen_in

Usage:
    python generate_player_ids.py

"""
import csv
import glob
import os
from datetime import datetime

DATA_DIR = os.path.join(os.path.dirname(__file__), 'data')
PATTERN = os.path.join(DATA_DIR, '*-ayhl-rosters.csv')


def main():
    files = sorted(glob.glob(PATTERN))
    if not files:
        print('No rosters files found matching pattern:', PATTERN)
        return

    seen = {}
    for fp in files:
        with open(fp, 'r', encoding='utf-8') as fh:
            reader = csv.DictReader(fh)
            for row in reader:
                # common column name is 'playerid'
                pid = (row.get('playerid') or '').strip()
                name = (row.get('player') or row.get('player name') or row.get('player_name') or '').strip()
                if not pid:
                    continue
                # normalize numeric ids only
                if not pid.isdigit():
                    # sometimes quotes or stray characters; try extract digits
                    import re
                    m = re.search(r'(\d+)', pid)
                    if m:
                        pid = m.group(1)
                    else:
                        continue
                if pid not in seen:
                    seen[pid] = {'first_seen': os.path.basename(fp), 'name': name}
                else:
                    # if we don't have a name yet and this row has one, store it
                    if not seen[pid].get('name') and name:
                        seen[pid]['name'] = name

    if not seen:
        print('No playerids found in roster files.')
        return

    ts = datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')
    out_fname = os.path.join(DATA_DIR, f'ayhl-player-id-{ts}.csv')
    with open(out_fname, 'w', newline='', encoding='utf-8') as outfh:
        writer = csv.writer(outfh)
        writer.writerow(['playerid', 'player_name', 'first_seen_in'])
        for pid, info in sorted(seen.items(), key=lambda kv: int(kv[0])):
            writer.writerow([pid, info.get('name', ''), info.get('first_seen', '')])

    print(f'Wrote {len(seen)} unique playerids to: {out_fname}')


if __name__ == '__main__':
    main()
