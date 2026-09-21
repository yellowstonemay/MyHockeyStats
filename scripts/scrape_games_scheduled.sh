#!/bin/bash
# scrape_games_scheduled.sh  —  run scrape_games.js on a schedule.
#
# Modes:
#   targeted  -> scrape ONLY the THF/AHF teams that contain a registered-user
#                identity link or a followed player this season (cheap, daily).
#   full      -> scrape every THF/AHF team for the season (weekly catch-all).
#
# Why two-stage: scrape_games.js fills thf_games/ahf_games with new game results
# + parsed scoresheets. The 6:00 AM run_deep_dive.sh then rolls those into each
# player's *_player_games (game history). This script should run BEFORE 6:00 AM.
#
# Requires the OPTIMIZED scrape_games.js (concurrency + throttled downloads) and
# a PYTHON that has pdfplumber (the deep-dive venv). Uses DB env vars below.

MODE="${1:-targeted}"

export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
export DB_HOST=localhost DB_PORT=5432 DB_NAME=hockey_stats DB_USER=admin DB_PASSWORD=your_secure_password
export PYTHON="$HOME/hockey-server/scripts/deep_dive/.venv/bin/python"

cd "$HOME/hockey-server/scripts/thf-js" || { echo "thf-js dir not found"; exit 1; }
LOG_DIR="$HOME/hockey-server/logs"
mkdir -p "$LOG_DIR"

# Current THF/AHF season year (Sep-Mar cycle): Aug-Dec -> that year, Jan-Jul -> prior.
M=$(date '+%-m')
Y=$(date '+%-Y')
if [ "$M" -ge 8 ]; then SEASON=$Y; else SEASON=$((Y - 1)); fi

TS=$(date '+%Y-%m-%d %H:%M:%S')
echo "=================================================================="
echo " scrape_games_scheduled  mode=$MODE  season=$SEASON  at $TS"
echo "=================================================================="

scrape_league() {
  local LEAGUE="$1"
  local CSV="data/${SEASON}-${LEAGUE}-teams.csv"
  local BACKUP="/tmp/${SEASON}-${LEAGUE}-teams.csv.full"
  [ -f "$CSV" ] || { echo "no teams csv $CSV - skipping $LEAGUE"; return; }

  cp "$CSV" "$BACKUP"

  if [ "$MODE" = "targeted" ]; then
    # Source tag used in player_source_links / follows
    local TAG; [ "$LEAGUE" = "thf" ] && TAG=THF || TAG=AHF
    local TEAMIDS
    TEAMIDS=$(docker exec -i hockey-postgres psql -U admin -d hockey_stats -t -A \
      -c "SELECT DISTINCT r.team_id FROM ${LEAGUE}_rosters r
          WHERE r.season_year=${SEASON}
            AND r.player_id::text IN (
              SELECT source_player_id FROM player_source_links WHERE source='${TAG}'
              UNION
              SELECT source_player_id FROM follows WHERE source='${TAG}'
            );")
    TEAMIDS=$(echo "$TEAMIDS" | tr -d ' ' | grep -v '^$')
    if [ -z "$TEAMIDS" ]; then
      echo "no relevant ${LEAGUE} teams for registered/followed players this season - restoring csv, skipping"
      cp "$BACKUP" "$CSV"; rm -f "$BACKUP"; return
    fi
    local PATTERN=$(echo "$TEAMIDS" | paste -sd '|' -)
    { head -1 "$BACKUP"; grep -E "^${SEASON},(${PATTERN})," "$BACKUP"; } > "$CSV"
    echo "[$LEAGUE] targeted teams: $(wc -l < "$CSV") rows (team_ids: $(echo "$TEAMIDS" | paste -sd ' ' -))"
  else
    echo "[$LEAGUE] full run: all teams ($(wc -l < "$CSV") rows incl header)"
  fi

  echo ">>> node scrape_games.js $LEAGUE $SEASON --no-csv"
  node scrape_games.js "$LEAGUE" "$SEASON" --no-csv
  local RC=$?

  # restore the full teams CSV regardless of node result
  cp "$BACKUP" "$CSV"
  rm -f "$BACKUP"
  echo "[$LEAGUE] scrape exit=$RC - teams csv restored"
}

scrape_league thf
scrape_league ahf

echo "scrape_games_scheduled finished at $(date '+%Y-%m-%d %H:%M:%S')"
