# Setup
mkdir hockey-scraper
cd hockey-scraper
npm init -y
npm install puppeteer csv-writer csv-parse
npx puppeteer browsers install chrome

# Usage
# 1) Scrape teams for a season
node scrape_teams.js thf 2025
node scrape_teams.js ahf 2025

# 2) Scrape rosters for a season
node scrape_rosters.js thf 2025
node scrape_rosters.js ahf 2025

# 2b) Scrape team schedules/games for a season and download scoresheets
# (DB sync is enabled by default)
node scrape_games.js thf 2025
node scrape_games.js ahf 2025

# 2c) Optional: bypass DB sync
node scrape_games.js thf 2025 --no-db-sync
node scrape_games.js ahf 2025 --no-db-sync

# 2d) Optional: bypass scraping and sync existing games CSV to DB
node scrape_games.js thf 2025 --csv-sync
node scrape_games.js ahf 2025 --csv-sync
node scrape_games.js thf 2025 --csv-sync --csv=./custom-games.csv

# 2e) Optional: run DB-first pipeline without writing games CSV
node scrape_games.js thf 2025 --no-csv
node scrape_games.js ahf 2025 --no-csv

# 3) Re-scan only failed teams from failed_rosters.txt
node scrape_rosters.js thf 2025 --failed-only
node scrape_rosters.js ahf 2025 --failed-only

# 4) Resume from last team_id in existing roster output
node scrape_rosters.js thf 2025 --resume
node scrape_rosters.js ahf 2025 --resume

# 4b) Resume/failed-only modes for games
# Resume is DB-based when DB sync is enabled (default)
node scrape_games.js thf 2025 --resume
node scrape_games.js thf 2025 --failed-only
node scrape_games.js ahf 2025 --resume
node scrape_games.js ahf 2025 --failed-only

# 4c) You can combine game modes with DB sync
node scrape_games.js thf 2025 --resume
node scrape_games.js thf 2025 --failed-only

# 5) Parse scoresheet PDF(s) into JSON
python parse_scoresheet.py data/scoresheet/thf/2025/43061.pdf
python parse_scoresheet.py data/scoresheet/thf/2025/*.pdf
python parse_scoresheet.py data/scoresheet/ahf/2025/*.pdf

# 5b) Optional output directory
python parse_scoresheet.py data/scoresheet/thf/2025/*.pdf --out-dir data/scoresheet/thf/2025/json

# Output files
# Teams:
#   data/<seasonYear>-thf-teams.csv
#   data/<seasonYear>-ahf-teams.csv
# Rosters:
#   <seasonYear>-thf-rosters.csv
#   <seasonYear>-ahf-rosters.csv
# Games:
#   <seasonYear>-thf-games.csv
#   <seasonYear>-ahf-games.csv
# Scoresheets:
#   data/scoresheet/<league>/<seasonYear>/<game_id>.pdf
# Scoresheet parsed JSON:
#   data/scoresheet/<league>/<seasonYear>/<game_id>.json
# Roster entries include stats directly per player:
#   visitor_roster["<jersey>"] => { name, goals, assists, points, pims }
#   home_roster["<jersey>"]    => { name, goals, assists, points, pims }
# Failures after retries:
#   failed_rosters.txt
#   failed_games.txt