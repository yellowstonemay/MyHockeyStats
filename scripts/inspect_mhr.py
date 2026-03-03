"""
Diagnostic tool to inspect myhockeyrankings.com page structure
and find the correct CSS selectors for scraping.
"""

import argparse
from playwright.sync_api import sync_playwright

def inspect_page(name, birthyear):
    """Load the search results page and show page structure."""
    
    search_url = f"https://myhockeyrankings.com/jsp/aSearch.jsp?lastName={name}&birthYear={birthyear}"
    
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        try:
            print(f"🔍 Loading search results page...")
            page.goto(search_url, timeout=15000)
            print(f"✓ Page loaded\n")
            
            # Get page title
            title = page.title()
            print(f"📄 Page Title: {title}\n")
            
            # Check for common table selectors
            selectors_to_check = [
                "table",
                "table tbody tr",
                ".player",
                ".players",
                ".result",
                ".results",
                "[class*='player']",
                "[class*='table']",
                "div.content table",
                "table.results",
                "table.players",
                "div[class*='result']",
            ]
            
            print("🔎 Checking CSS selectors:\n")
            found_selectors = []
            
            for selector in selectors_to_check:
                try:
                    elements = page.query_selector_all(selector)
                    if elements:
                        print(f"✓ Found {len(elements):3d} elements with: {selector}")
                        found_selectors.append((selector, len(elements)))
                except:
                    pass
            
            if found_selectors:
                print(f"\n✅ Most promising selectors (by count):")
                for selector, count in sorted(found_selectors, key=lambda x: x[1], reverse=True)[:5]:
                    print(f"   {selector} ({count} matches)")
            
            # Get HTML snippet to analyze structure
            print(f"\n📋 Page HTML structure (first 2000 chars):")
            print("-" * 60)
            html = page.content()
            print(html[:2000])
            print("...")
            
            browser.close()
            
        except Exception as e:
            browser.close()
            print(f"❌ Error: {e}")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Inspect myhockeyrankings.com page structure")
    parser.add_argument('--name', default='Ethan Yan', help='Player name')
    parser.add_argument('--year', type=int, default=2011, help='Birth year')
    args = parser.parse_args()
    
    inspect_page(args.name, args.year)
