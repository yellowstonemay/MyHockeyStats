#!/bin/bash
export PATH="$HOME/.local/bin:/usr/local/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
export DB_HOST=localhost DB_PORT=5432 DB_NAME=hockey_stats DB_USER=admin DB_PASSWORD=your_secure_password
cd ~/hockey-server/scripts/ayhl || exit 1
PY=../deep_dive/.venv/bin/python
Q() { docker exec hockey-postgres psql -U admin -d hockey_stats -t -A -F'|' -c "$1"; }

echo "=== does the API still serve old seasons? try 2015 and 2022 ==="
for Y in 2015 2022; do
  echo "--- $Y ---"
  $PY scrape_scores.py --season-year $Y 2>&1 | tail -3
done

echo
echo "=== with_score by season (spot check) ==="
Q "SELECT season_year, COUNT(*) FILTER (WHERE score_for IS NOT NULL) AS scored, COUNT(*) AS games
   FROM ayhl_player_games WHERE season_year IN (2015, 2022, 2024) GROUP BY 1 ORDER BY 1;"
