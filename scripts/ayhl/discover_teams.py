"""
Phase 1: Discover leagues and teams from Atlantic Hockey (atlantichockey.org).

This script:
1. Accepts a season year (e.g. `--season 2025` for 2025-2026 season)
2. Maps it to site's seasonid
3. Fetches the season page and parses the league dropdown (select[name="league"])
4. For each league, discovers teams from the team dropdown (select[name="team"])
5. Saves league-team list to CSV (e.g., 2025-ayhl-teams.csv)

This CSV is then used by scrape_rosters.py to fetch actual roster data.

Usage:
  python discover_teams.py --season 2025
  python discover_teams.py --season 2025 --sample
"""

import argparse
import csv
import time
from playwright.sync_api import sync_playwright

# Mapping from provided season year to site seasonid
SEASON_TO_ID = {
    2025: 33,
    2024: 32,
    2023: 31,
    2022: 30,
    2021: 28,
    2020: 27,
    2019: 26,
    2018: 25,
    2017: 24,
    2016: 23,
    2015: 22,
    2014: 21,
    2013: 20,
    2012: 19,
    2011: 17,
    2010: 16,
    2009: 15,
    2008: 14,
    2007: 13,
    2006: 12,
    2005: 11,
    2004: 10,
}


def discover_leagues_from_season(page, seasonid):
    """
    Discover all available leagues for a season by parsing the league dropdown.
    Returns list of dicts: [{'name': 'League Name', 'leagueid': 279, 'params': '?leagueid=279&leaguetypeid=2&seasonid=33'}, ...]
    """
    season_page_url = f"https://atlantichockey.org/teamroster.php?seasonid={seasonid}"
    
    try:
        page.goto(season_page_url, timeout=15000)
    except Exception as e:
        print(f"Error loading season page: {e}")
        return []
    
    leagues = []
    
    # Parse the league dropdown (select[name="league"])
    league_options = page.query_selector_all('select[name="league"] option')
    
    for opt in league_options:
        opt_value = opt.get_attribute('value')
        opt_text = opt.inner_text().strip()
        
        # Skip empty or default option (usually the first one)
        if not opt_value or opt_value.startswith('?seasonid=') and '&leagueid=' not in opt_value:
            continue
        
        # Parse leagueid from value (e.g., "?leagueid=279&leaguetypeid=2&seasonid=33")
        leagueid = None
        if opt_value.startswith('?'):
            params = opt_value[1:]  # remove leading '?'
            for param in params.split('&'):
                if param.startswith('leagueid='):
                    leagueid = int(param.split('=')[1])
                    break
        
        if leagueid is not None:
            leagues.append({
                'name': opt_text,
                'leagueid': leagueid,
                'params': opt_value,
            })
    
    print(f"Discovered {len(leagues)} leagues for season {seasonid}: {[l['name'] for l in leagues]}")
    return leagues


def discover_teams_from_league(page, league_params):
    """
    Discover all teams for a league by parsing the team dropdown.
    league_params is like: "?leagueid=279&leaguetypeid=2&seasonid=33"
    Returns list of dicts: [{'name': 'Team Name', 'params': '?seasonid=33&leaguetypeid=2&leagueid=281&teamid=3301'}, ...]
    """
    league_page_url = f"https://atlantichockey.org/teamroster.php{league_params}"
    
    try:
        page.goto(league_page_url, timeout=15000)
    except Exception as e:
        print(f"Error loading league page: {e}")
        return []
    
    teams = []
    
    # Parse the team dropdown (select[name="team"])
    team_options = page.query_selector_all('select[name="team"] option')
    
    for i, opt in enumerate(team_options):
        # Skip the first option (default "- Teams-")
        if i == 0:
            continue
        
        opt_value = opt.get_attribute('value')
        opt_text = opt.inner_text().strip()
        
        if not opt_value or not opt_text:
            continue
        
        teams.append({
            'name': opt_text,
            'params': opt_value,
        })
    
    return teams


def main():
    parser = argparse.ArgumentParser(description="Discover leagues and teams from Atlantic Hockey")
    parser.add_argument('--season', type=int, required=True, help='Season year (e.g. 2025 for 2025-2026)')
    parser.add_argument('--delay', type=float, default=0.02, help='Delay between requests (seconds)')
    parser.add_argument('--sample', action='store_true', help='Sample mode (limit to 2 leagues, 3 teams each)')
    args = parser.parse_args()

    seasonid = SEASON_TO_ID.get(args.season)
    if seasonid is None:
        print(f"Unknown season {args.season}. Please provide a supported season year.")
        return

    # Write teams CSV into the data/teams subdirectory
    output_dir = os.path.join(os.path.dirname(__file__), 'data', 'teams')
    if not os.path.exists(output_dir):
        os.makedirs(output_dir, exist_ok=True)
    output_file = os.path.join(output_dir, f"{args.season}-ayhl-teams.csv")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        print(f"\n{'='*70}")
        print(f"PHASE 1: DISCOVER LEAGUES AND TEAMS")
        print(f"{'='*70}\n")
        
        print(f"Step 1: Discovering leagues for season {args.season} (seasonid={seasonid})...")
        leagues = discover_leagues_from_season(page, seasonid)
        
        if not leagues:
            print("No leagues found. Exiting.")
            browser.close()
            return
        
        if args.sample:
            leagues = leagues[:2]
            print(f"Sample mode: limiting to first 2 leagues\n")
        
        # Discover all teams
        league_team_list = []
        
        for league in leagues:
            print(f"  Discovering teams in '{league['name']}'...")
            teams = discover_teams_from_league(page, league['params'])
            print(f"    Found {len(teams)} teams")
            
            if args.sample:
                teams = teams[:3]
                print(f"    Sample mode: limiting to first 3 teams")
            
            for team in teams:
                league_team_list.append({
                    'season_year': args.season,
                    'seasonid': seasonid,
                    'leagueid': league['leagueid'],
                    'league_name': league['name'],
                    'team_name': team['name'],
                    'team_params': team['params'],
                })
            
            # Polite delay between leagues
            time.sleep(args.delay)
        
        print(f"\n✅ Discovery complete: {len(league_team_list)} league-team pairs found")
        
        # Save discovered league-team list to CSV
        print(f"\nSaving discovered teams to '{output_file}'...")
        with open(output_file, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = ['season_year', 'seasonid', 'leagueid', 'league_name', 'team_name', 'team_params']
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(league_team_list)
        print(f"✅ Saved {len(league_team_list)} league-team pairs to '{output_file}'")
        print(f"\nNext step: Run 'python scrape_rosters.py --season {args.season}'")
        
        browser.close()


if __name__ == '__main__':
    main()
