import json, glob, os
base = os.path.expanduser("~/hockey-server/scripts/ayhl/data")
cj = os.path.join(base, "ayhl-player-career.json")
print("career json exists:", os.path.exists(cj), cj)
if os.path.exists(cj):
    data = json.load(open(cj, encoding="utf-8"))
    p = data.get("121730")
    print("player 121730 keys:", list(p.keys()) if isinstance(p, dict) else p)
    if isinstance(p, dict):
        for e in p.get("career", []):
            print("   ", {k: e.get(k) for k in ("Season", "League", "Teams", "Games", "Goals", "Assists", "Points", "PIM")})

print("\n--- saved player page tables for 121730 ---")
for f in sorted(glob.glob(os.path.join(base, "player_pages", "player_121730*"))):
    print("FILE:", f)
    if f.endswith(".csv"):
        with open(f, encoding="utf-8") as fh:
            txt = fh.read()
        print(txt[:600])
        print("...")
