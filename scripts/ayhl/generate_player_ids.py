"""Generate a unique AYHL playerid list from all '*-ayhl-rosters.csv' files.

Writes output to: scripts/ayhl/data/ayhl-player-id.csv (stable filename)
Columns: playerid, player_name, birthdate

Usage:
    python generate_player_ids.py

"""
import csv
import glob
import os
from datetime import datetime

BASE_DIR = os.path.join(os.path.dirname(__file__), 'data')
# roster files were moved into the 'rosters' subdirectory
ROSTER_DIR = os.path.join(BASE_DIR, 'rosters')
PATTERN = os.path.join(ROSTER_DIR, '*-ayhl-rosters.csv')
OUT_DIR = BASE_DIR


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
                # try common birthdate column names
                birthdate = (row.get('birthdate') or row.get('bd') or row.get('birth') or '').strip()
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
                    seen[pid] = {'name': name, 'birthdate': birthdate}
                else:
                    # prefer a non-empty name or birthdate if we don't have one yet
                    if not seen[pid].get('name') and name:
                        seen[pid]['name'] = name
                    if not seen[pid].get('birthdate') and birthdate:
                        seen[pid]['birthdate'] = birthdate

    if not seen:
        print('No playerids found in roster files.')
        return

    out_fname = os.path.join(OUT_DIR, 'ayhl-player-id.csv')
    with open(out_fname, 'w', newline='', encoding='utf-8') as outfh:
        writer = csv.writer(outfh)
        writer.writerow(['playerid', 'player_name', 'birthdate'])
        for pid, info in sorted(seen.items(), key=lambda kv: int(kv[0])):
            writer.writerow([pid, info.get('name', ''), info.get('birthdate', '')])

    print(f'Wrote {len(seen)} unique playerids to: {out_fname}')


if __name__ == '__main__':
    main()
