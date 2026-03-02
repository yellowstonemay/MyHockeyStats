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
        'url': 'https://ayhl.com/search',
        'name_selector': "input[name='q']",
        'year_selector': "input[name='y']",
        'submit_selector': "button.search",
        'result_rows': "div.player-list > div.player",
    },
}


def scrape(league, name, birthyear):
    cfg = LEAGUE_CONFIG.get(league)
    if not cfg:
        raise ValueError(f"Unsupported league: {league}")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto(cfg['url'])
        time.sleep(1)  # allow page to load

        page.fill(cfg['name_selector'], name)
        page.fill(cfg['year_selector'], str(birthyear))
        page.click(cfg['submit_selector'])

        # wait for results container (simple heuristic)
        page.wait_for_selector(cfg['result_rows'], timeout=5000)

        rows = page.query_selector_all(cfg['result_rows'])
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--league', required=True, choices=LEAGUE_CONFIG.keys())
    parser.add_argument('--name', required=True)
    parser.add_argument('--year', type=int, required=True)
    args = parser.parse_args()

    results = scrape(args.league, args.name, args.year)
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
