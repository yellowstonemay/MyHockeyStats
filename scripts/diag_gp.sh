#!/bin/bash
export PATH="/usr/local/bin:$PATH"
P() { docker exec -i hockey-postgres psql -U admin -d hockey_stats -t -A -F' | ' -c "$1"; }
echo "=== ayhl_player_career for 121730 (all seasons) ==="
P "SELECT season_label, team_name, games_played, goals, assists, points, pim, last_scraped_at, is_user_modified FROM ayhl_player_career WHERE source_player_id='121730' ORDER BY season_label DESC;"
echo
echo "=== ayhl_player_games count for 121730 by season ==="
P "SELECT season_year, season_label, count(*) FROM ayhl_player_games WHERE player_id='121730' GROUP BY season_year, season_label ORDER BY season_year DESC;"
echo
echo "=== what the seasons endpoint would show: join career+roster for 121730 ==="
P "SELECT c.season_label, c.team_name, c.games_played, c.goals, c.assists, c.points, c.pim FROM ayhl_player_career c WHERE c.source_player_id='121730' ORDER BY c.season_label DESC LIMIT 5;"
