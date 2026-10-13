import re
import requests

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
html = requests.get("https://atlantichockey.org/playerpage.php",
                    params={"playerid": "121730"},
                    headers={"User-Agent": UA}, timeout=25).text
print("len", len(html))

tables = re.findall(r"<table.*?>.*?</table>", html, flags=re.S | re.I)
career = None
for tbl in tables:
    if re.search(r">\s*Season\s*<", tbl, flags=re.I):
        career = tbl
        break
print("career table found:", career is not None)

tr_blocks = re.findall(r"(<tr.*?>.*?</tr>)", career, flags=re.S | re.I)
headers = []
hidx = None
for i, tr in enumerate(tr_blocks):
    if re.search(r"<th", tr, flags=re.I):
        ths = re.findall(r"<th[^>]*>(.*?)</th>", tr, flags=re.S | re.I)
        headers = [re.sub(r"<.*?>", "", t).strip() for t in ths]
        hidx = i
        break
if not headers and tr_blocks:
    cells = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr_blocks[0], flags=re.S | re.I)
    headers = [re.sub(r"<.*?>", "", c).strip() for c in cells]
    hidx = 0
print("header_tr_idx", hidx, "headers:", headers)

for i, tr in enumerate(tr_blocks):
    if i == hidx:
        continue
    tds = re.findall(r"<td[^>]*>(.*?)</td>", tr, flags=re.S | re.I)
    if not tds:
        tds = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, flags=re.S | re.I)
    if not tds:
        continue
    clean = [re.sub(r"<.*?>", "", td).strip() for td in tds]
    print(i, clean)
