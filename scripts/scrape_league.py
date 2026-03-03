"""League scraper prototype for THF/AHF/AYHL.

This script uses Playwright to open a headless browser, submit the search
form on the league website, and extract player data tables. It can be run
locally or from a backend job worker. The scraped rows are printed as JSON;
the calling application can insert them into the database.

Requirements:
    pip install playwright
    playwright install

Usage:
    python scripts/scrape_league.py --league THF --name "John Doe" --year 2010
"""

import argparse
import json
import time

from playwright.sync_api import sync_playwright

LEAGUE_CONFIG = {
    'THF': {
        'url': 'https://www.thfhockey.com/search-player',
        'name_selector': "input[name='playerName']",
        'year_selector': "input[name='birthYear']",
        'submit_selector': "button[type='submit']",
        'result_rows': "table.results tbody tr",
    },
    'AHF': {
        'url': 'https://www.ahfexample.org/find',
        # selectors would need to be determined by inspecting the real site
        'name_selector': "#nameInput",
        'year_selector': "#yearInput",
        'submit_selector': "#searchButton",
        'result_rows': "table#results tbody tr",
    },
    'AYHL': {
        'url': 'https://myhockeyrankings.com/default.asp',
        'use_search_endpoint': True,
        # For myhockeyrankings.com, we use a direct search via URL parameter
        # Example: https://myhockeyrankings.com/search?name=John&year=2010
        'search_endpoint': 'https://myhockeyrankings.com/jsp/aSearch.jsp',
        'name_param': 'lastName',  # parameter name in search form
        'year_param': 'birthYear',
        'result_rows': "table.playerTable tbody tr",  # May need adjustment
    },
}


def scrape(league, name, birthyear, timeout=10000):
    cfg = LEAGUE_CONFIG.get(league)
    if not cfg:
        raise ValueError(f"Unsupported league: {league}")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        try:
            # Handle different scraping strategies based on league config
            if cfg.get('use_search_endpoint'):
                # For myhockeyrankings.com: use direct search endpoint
                print(f"📄 Searching myhockeyrankings.com for '{name}' (birth year {birthyear})...")
                search_url = f"{cfg['search_endpoint']}?{cfg['name_param']}={name}&{cfg['year_param']}={birthyear}"
                print(f"   URL: {search_url}")
                page.goto(search_url, timeout=15000)
            else:
                # Standard form-based scraping
                print(f"📄 Loading {cfg['url']}...")
                page.goto(cfg['url'], timeout=15000)
                time.sleep(1)  # allow page to load
                print(f"✓ Page loaded successfully")

                print(f"🔍 Filling form: name={name}, year={birthyear}")
                page.fill(cfg['name_selector'], name)
                page.fill(cfg['year_selector'], str(birthyear))
                page.click(cfg['submit_selector'])
                print(f"✓ Form submitted")

            # Wait for results
            print(f"⏳ Waiting for results (timeout: {timeout}ms)...")
            page.wait_for_selector(cfg['result_rows'], timeout=timeout)
            print(f"✓ Results loaded")

            rows = page.query_selector_all(cfg['result_rows'])
            print(f"📊 Found {len(rows)} rows")
            
            parsed = []
            for r in rows:
                cols = r.query_selector_all('td')
                if cols:
                    parsed.append([c.inner_text().strip() for c in cols])
                else:
                    # fallback: take text content
                    parsed.append(r.inner_text().strip())

            browser.close()
            return parsed
            
        except Exception as e:
            browser.close()
            print(f"❌ Error during scraping: {type(e).__name__}")
            print(f"   Details: {str(e)}")
            print(f"\n📝 Configuration used for league '{league}':")
            for k, v in cfg.items():
                print(f"   {k}: {v}")
            print(f"\n💡 Debugging tip:")
            print(f"   Visit the website and inspect the HTML to find correct selectors:")
            print(f"   - Right-click on the player table → Inspect")
            print(f"   - Find the <table> tag and note its class/id")
            print(f"   - Update the 'result_rows' selector in LEAGUE_CONFIG")
            raise


def main():
    parser = argparse.ArgumentParser(description="Scrape player data from hockey leagues")
    parser.add_argument('--league', required=True, choices=LEAGUE_CONFIG.keys(),
                        help='League to scrape: THF, AHF, or AYHL')
    parser.add_argument('--name', required=True, help='Player name to search')
    parser.add_argument('--year', type=int, required=True, help='Birth year (YYYY)')
    parser.add_argument('--timeout', type=int, default=10000, help='Timeout in ms (default: 10000)')
    args = parser.parse_args()

    try:
        print(f"\n🏒 Hockey League Scraper")
        print(f"{'='*50}")
        print(f"League: {args.league}")
        print(f"Player: {args.name}")
        print(f"Birth Year: {args.year}")
        print(f"{'='*50}\n")
        
        results = scrape(args.league, args.name, args.year, timeout=args.timeout)
        
        print(f"\n✅ Scraping completed successfully!")
        print(f"Found {len(results)} records\n")
        print("Results (JSON):")
        print(json.dumps(results, indent=2))
        
    except Exception as e:
        print(f"\n⚠️  Scraping failed.")
        print(f"\nNote: The configured CSS selectors may not match the current website structure.")
        print(f"To fix this, you need to:")
        print(f"  1. Visit the league website manually")
        print(f"  2. Inspect the HTML to find the correct CSS selectors")
        print(f"  3. Update the LEAGUE_CONFIG dictionary with the correct selectors\n")
        exit(1)


if __name__ == '__main__':
    main()
