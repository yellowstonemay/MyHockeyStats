================================================================================
ATLANTIC HOCKEY LEAGUE (AYHL) ROSTER SCRAPER
================================================================================

This directory contains scripts to discover and scrape roster data from 
Atlantic Hockey League (atlantichockey.org).

================================================================================
TWO-PHASE WORKFLOW
================================================================================

The scraper is split into two independent phases:

PHASE 1: DISCOVERY (discover_teams.py)
  - Discovers all leagues for a given season
  - For each league, discovers all teams in that league
  - Outputs: {season}-ayhl-teams.csv (e.g., 2025-ayhl-teams.csv)
  - Run once per season (or when you want to refresh the team list)

PHASE 2: SCRAPING (scrape_rosters.py)
  - Reads a teams CSV file (e.g., from Phase 1)
  - For each team, scrapes the player roster
  - Outputs a corresponding roster CSV (e.g., 2025-ayhl-rosters.csv)
  - Can run multiple times without re-running Phase 1

================================================================================
USAGE WORKFLOW
================================================================================

1. PHASE 1: Discover leagues and teams (one time per season)
   
   cd scripts/ayhl
   python discover_teams.py --season 2025
   
   This will:
   - Connect to atlantichockey.org
   - Parse the league dropdown on the season page
   - For each league, parse the team dropdown
   - Save all league-team pairs to: 2025-ayhl-teams.csv

2. PHASE 2: Scrape rosters (can run multiple times)
   
   python scrape_rosters.py 2025-ayhl-teams.csv
   
   This will:
   - Read the team list from the specified file (2025-ayhl-teams.csv)
   - For each team, navigate to the roster page and extract player data
   - Save all player records to: 2025-ayhl-rosters.csv (inferred from the input file name)

3. INSPECT & ITERATE
   
   You can edit 2025-ayhl-teams.csv to remove teams, then re-run Phase 2:
   
   python scrape_rosters.py 2025-ayhl-teams.csv

================================================================================
SAMPLE MODE (Testing/Debugging)
================================================================================

Test with a smaller dataset:

1. Discover teams (limited to first 2 leagues, 3 teams each):
   
   python discover_teams.py --season 2025 --sample
   
   Output: 2025-ayhl-teams.csv (with ~6 teams instead of ~150)

2. Scrape rosters for those teams:
   
   python scrape_rosters.py 2025-ayhl-teams.csv
   
   Output: 2025-ayhl-rosters.csv (with ~50-100 players instead of thousands)

================================================================================
COMMAND-LINE OPTIONS
================================================================================

discover_teams.py:
  --season YEAR         Season year (e.g., 2025 for 2025-2026 season) [REQUIRED]
  --delay SECONDS       Delay between requests in seconds (default: 0.02)
  --sample              Sample mode: limit to 2 leagues, 3 teams each
  --help                Show help message

scrape_rosters.py:
  input_file            Input CSV file with discovered teams (e.g., "2025-ayhl-teams.csv") [REQUIRED]
  --output FILE         Output CSV file for rosters (default: auto-generated from input file name)
  --delay SECONDS       Delay between requests in seconds (default: 0.02)
  --help                Show help message

================================================================================
OUTPUT FILES
================================================================================

2025-ayhl-teams.csv (Phase 1 output)
  Columns: season_year, seasonid, leagueid, league_name, team_name, team_params
  Example rows:
    2025, 33, 279, "10U Major 15", "Long Island Gulls", "?seasonid=33&leaguetypeid=2&leagueid=279&teamid=3301"
    2025, 33, 279, "10U Major 15", "Long Island Royals", "?seasonid=33&leaguetypeid=2&leagueid=279&teamid=3302"

2025-ayhl-rosters.csv (Phase 2 output)
  Columns: season_year, seasonid, leagueid, teamid, team, number, player, pos, ht, wt, shot, birthdate, hometown
  Example rows:
    2025, 33, 279, 3301, "Long Island Gulls", "1", "John Doe", "C", "5'10\"", "165", "R", "2012-03-15", "New York, NY"
    2025, 33, 279, 3301, "Long Island Gulls", "2", "Jane Smith", "LW", "5'8\"", "158", "L", "2012-06-22", "Long Island, NY"

================================================================================
SUPPORTED SEASONS
================================================================================

The SEASON_TO_ID mapping in discover_teams.py supports:
  2025 (2025-2026)
  2024 (2024-2025)
  2023 (2023-2024)
  ... down to ...
  2004 (2004-2005)

If you need to add more seasons, update the SEASON_TO_ID dictionary in discover_teams.py.

================================================================================
TIPS & NOTES
================================================================================

• Each phase has a delay between requests (default 0.02 seconds) to be polite
  to the server. Adjust with --delay if needed.

• Sample mode (--sample) is useful for testing the scraper without waiting
  for the entire dataset.

• If Phase 1 (discovery) takes a long time, you only need to run it once
  per season. Reuse the CSV for Phase 2.

• If you want to re-discover teams for a season, just re-run Phase 1.
  It will overwrite the previous CSV.

• Phase 2 can be interrupted and resumed. It just processes teams from
  the input CSV sequentially.

================================================================================
DEPRECATED
================================================================================

The older script scrape_atlantic_hockey.py combined both phases into one.
It is no longer maintained. Use discover_teams.py and scrape_rosters.py instead.

================================================================================
