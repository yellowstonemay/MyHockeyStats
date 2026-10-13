#!/bin/bash
CSV=~/hockey-server/scripts/ayhl/data/ayhl-player-career.csv
echo "=== exists ==="; ls -la "$CSV" 2>&1
echo "=== header ==="; head -1 "$CSV"
echo "=== 121730 rows ==="; grep 121730 "$CSV" | head -12
rm -f /tmp/show_csv.sh
