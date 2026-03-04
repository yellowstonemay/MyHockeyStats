"""
Test Step 2: Discover teams from league page
"""

from playwright.sync_api import sync_playwright
from scrape_atlantic_hockey import discover_leagues_from_season, discover_teams_from_league, SEASON_TO_ID

def test_team_discovery():
    season = 2025
    seasonid = SEASON_TO_ID.get(season)
    
    if seasonid is None:
        print(f"Unknown season {season}")
        return
    
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        # Step 1: Get leagues
        print(f"Step 1: Discovering leagues for season {season}")
        print("=" * 70)
        leagues = discover_leagues_from_season(page, seasonid)
        
        if not leagues:
            print("No leagues found!")
            browser.close()
            return
        
        # Limit to first 2 for testing
        test_leagues = leagues[:2]
        
        for league in test_leagues:
            print(f"\nStep 2: Discovering teams for league: {league['name']}")
            print("-" * 70)
            
            # Step 2: Get teams in this league
            teams = discover_teams_from_league(page, league['params'])
            
            print(f"Found {len(teams)} teams:\n")
            for i, team in enumerate(teams, 1):
                print(f"{i}. {team['name']}")
                print(f"   params: {team['params']}")
                
                # Extract teamid from params
                teamid = None
                if team['params'].startswith('?'):
                    params_str = team['params'][1:]
                    for param in params_str.split('&'):
                        if param.startswith('teamid='):
                            teamid = param.split('=')[1]
                            break
                if teamid:
                    print(f"   teamid: {teamid}")
                print()
        
        browser.close()

if __name__ == '__main__':
    test_team_discovery()
