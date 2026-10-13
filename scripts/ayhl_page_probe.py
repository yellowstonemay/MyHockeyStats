import re
import requests
from bs4 import BeautifulSoup

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
r = requests.get("https://atlantichockey.org/playerpage.php",
                 params={"playerid": "121730", "seasonid": "34"},
                 headers={"User-Agent": UA}, timeout=25)
print("status", r.status_code, "len", len(r.text))
soup = BeautifulSoup(r.text, "html.parser")

print("\n--- headings ---")
for h in soup.find_all(re.compile("^h[1-6]$")):
    print("  ", h.name, h.get_text(" ", strip=True))

print("\n--- tables (idx, rows, first-row cells) ---")
for i, t in enumerate(soup.find_all("table")):
    rows = t.find_all("tr")
    if not rows:
        continue
    cells = [c.get_text(" ", strip=True) for c in rows[0].find_all(["th", "td"])]
    print(f"  TABLE {i}: rows={len(rows)} hdr={cells[:14]}")

print("\n--- any '2026-2027' occurrences (context) ---")
txt = r.text
for m in re.finditer(r"2026-2027", txt):
    s = max(0, m.start() - 120)
    snippet = re.sub(r"\s+", " ", txt[s:m.start() + 160])
    print("   ...", snippet, "...")
