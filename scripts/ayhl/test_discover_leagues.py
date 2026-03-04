"""
Quick test of Step 1: Discover leagues from season page
"""

from playwright.sync_api import sync_playwright
from scrape_atlantic_hockey import discover_leagues_from_season, SEASON_TO_ID

def test_league_discovery():
    season = 2025
    seasonid = SEASON_TO_ID.get(season)
    
    if seasonid is None:
        print(f"Unknown season {season}")
        return
    
    print(f"Testing league discovery for season {season} (seasonid={seasonid})")
    print("=" * 60)
    
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        leagues = discover_leagues_from_season(page, seasonid)
        
        print(f"\n✅ Found {len(leagues)} leagues:")
        for i, league in enumerate(leagues, 1):
            print(f"\n{i}. {league['name']}")
            print(f"   leagueid: {league['leagueid']}")
            print(f"   params: {league['params']}")
        
        browser.close()

if __name__ == '__main__':
    test_league_discovery()
