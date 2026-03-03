"""
Inspect the main myhockeyrankings.com home page to find the search form.
"""

from playwright.sync_api import sync_playwright
import time

def inspect_home_page():
    """Load the home page and analyze the search form."""
    
    url = "https://myhockeyrankings.com/"
    
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        try:
            print(f"🔍 Loading {url}...")
            page.goto(url, timeout=15000)
            print(f"✓ Page loaded\n")
            
            # Get page title
            title = page.title()
            print(f"📄 Page Title: {title}\n")
            
            # Find all input fields
            inputs = page.query_selector_all('input')
            print(f"📝 Found {len(inputs)} input fields:")
            for i, inp in enumerate(inputs):
                try:
                    input_type = inp.get_attribute('type') or 'text'
                    input_name = inp.get_attribute('name') or '(no name)'
                    input_id = inp.get_attribute('id') or '(no id)'
                    placeholder = inp.get_attribute('placeholder') or '(no placeholder)'
                    print(f"   [{i}] type={input_type}, name={input_name}, id={input_id}, placeholder={placeholder}")
                except:
                    pass
            
            # Find all forms
            forms = page.query_selector_all('form')
            print(f"\n📋 Found {forms} forms")
            
            # Find all buttons
            buttons = page.query_selector_all('button')
            print(f"\n🔘 Found {len(buttons)} buttons:")
            for i, btn in enumerate(buttons):
                try:
                    btn_text = btn.inner_text()
                    btn_type = btn.get_attribute('type') or '(no type)'
                    btn_id = btn.get_attribute('id') or '(no id)'
                    print(f"   [{i}] text='{btn_text}', type={btn_type}, id={btn_id}")
                except:
                    pass
            
            # Get page content snippet
            print(f"\n📋 Page HTML (first 3000 chars):")
            print("-" * 60)
            html = page.content()
            print(html[:3000])
            print("\n... (truncated)")
            
            browser.close()
            
        except Exception as e:
            browser.close()
            print(f"❌ Error: {e}")

if __name__ == '__main__':
    inspect_home_page()
