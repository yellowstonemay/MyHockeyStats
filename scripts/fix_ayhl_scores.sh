#!/bin/bash
export PATH="$HOME/.local/bin:/usr/local/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
export DB_HOST=localhost DB_PORT=5432 DB_NAME=hockey_stats DB_USER=admin DB_PASSWORD=your_secure_password
cd ~/hockey-server/scripts/ayhl || exit 1
PY=../deep_dive/.venv/bin/python
Q() { docker exec hockey-postgres psql -U admin -d hockey_stats -t -A -F'|' -c "$1"; }

for Y in 2026 2025; do
  echo "=== season year $Y ==="
  $PY scrape_scores.py --season-year $Y 2>&1 | tail -4
done

echo
echo "=== AYHL games with a score, by season ==="
Q "SELECT season_year, COUNT(*) AS games, COUNT(*) FILTER (WHERE score_for IS NOT NULL) AS with_score
   FROM ayhl_player_games GROUP BY 1 ORDER BY 1;"
echo
echo "=== Ethan's 2026 AYHL results ==="
Q "SELECT game_date, team_for, team_against, score_for, score_against FROM ayhl_player_games
   WHERE player_id='121730' AND season_year=2026 ORDER BY game_date;"
