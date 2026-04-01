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

# 3) Re-scan only failed teams from failed_rosters.txt
node scrape_rosters.js thf 2025 --failed-only
node scrape_rosters.js ahf 2025 --failed-only

# 4) Resume from last team_id in existing roster output
node scrape_rosters.js thf 2025 --resume
node scrape_rosters.js ahf 2025 --resume

# Output files
# Teams:
#   data/<seasonYear>-thf-teams.csv
#   data/<seasonYear>-ahf-teams.csv
# Rosters:
#   <seasonYear>-thf-rosters.csv
#   <seasonYear>-ahf-rosters.csv
# Failures after retries:
#   failed_rosters.txt