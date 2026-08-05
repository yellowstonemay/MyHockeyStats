#!/usr/bin/env python3
"""Elite Prospects lookup poller (on-demand, user-initiated).

Processes `ep_lookup_requests` rows created by the backend:
  - SEARCH (name): hits the EP quicksearch JSON API -> candidates into ep_lookup_candidates
  - CAREER (ep_player_id): hits EP's Apollo GraphQL API -> rows into ep_player_career

No browser needed - both data sources are plain JSON APIs:
  * quicksearch:  GET  https://search.eliteprospects.com/api/quicksearch?q=...
  * career stats: POST https://gql.eliteprospects.com/  (Apollo persisted query,
                  operationName=PlayerStatisticsDefault). Requires
                  Content-Type: application/json + apollo-require-preflight to
                  pass EP's CSRF guard.

Cron (every minute):
    * * * * * /Users/ethan-macmini/hockey-server/scripts/deep_dive/.venv/bin/python /Users/ethan-macmini/hockey-server/scripts/deep_dive/ep_lookup.py --poll >> /Users/ethan-macmini/hockey-server/logs/ep-lookup.log 2>&1

Manual verification:
    python ep_lookup.py --search "Mason Sweeney"   # print candidates (no DB)
    python ep_lookup.py --career 1148194           # print career rows (no DB)
"""
from __future__ import annotations

import argparse
import os
import sys

import psycopg2
import psycopg2.extras
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_VENV_PY = os.path.join(SCRIPT_DIR, ".venv", "bin", "python")
PYTHON = os.environ.get("PYTHON", _VENV_PY if os.path.exists(_VENV_PY) else sys.executable)

QUICKSEARCH_URL = "https://search.eliteprospects.com/api/quicksearch"
GQL_URL = "https://gql.eliteprospects.com/"
CAREER_HASH = "2b19f87ae83e7cd9ee833de6abb875f88a7d641dfbea9aaba532493e6407536e"

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
HEADERS = {"User-Agent": UA, "Accept": "application/json"}


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


# ── search (plain HTTP JSON API) ───────────────────────────────────────────
def search_ep(name: str) -> list[dict]:
    resp = requests.get(QUICKSEARCH_URL, params={"q": name}, headers=HEADERS, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    if not data.get("success"):
        return []
    out = []
    for r in (data.get("data") or {}).get("results") or []:
        if r.get("_collection") != "players":
            continue
        out.append({
            "ep_player_id": str(r.get("id") or ""),
            "player_name": r.get("name"),
            "position": r.get("position"),
            "year_of_birth": r.get("yearOfBirth"),
            "latest_team": (r.get("latestTeam") or "").replace(" |NJ|", " NJ").strip(),
            "latest_league": r.get("latestLeague"),
            "league_experience": "; ".join(r.get("leagueExperience") or []),
        })
    return out


# ── career (Apollo GraphQL JSON API) ──────────────────────────────────────
def fetch_career(ep_id: str) -> list[dict]:
    payload = {
        "operationName": "PlayerStatisticsDefault",
        "variables": {
            "player": ep_id,
            "statsType": "default,projected",
            "leagueType": "league",
            "sort": "season",
        },
        "extensions": {"persistedQuery": {"version": 1, "sha256Hash": CAREER_HASH}},
    }
    headers = {**HEADERS, "Content-Type": "application/json", "apollo-require-preflight": "true"}
    resp = requests.post(GQL_URL, json=payload, headers=headers, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    edges = ((data.get("data") or {}).get("playerStats") or {}).get("edges") or []

    rows = []
    for e in edges:
        season = (e.get("season") or {}).get("slug")
        team = e.get("teamName")
        league = e.get("leagueName")
        if not team or not league:
            continue
        rs = e.get("regularStats") or {}
        rows.append({
            "season_label": season,
            "team_name": team,
            "league": league,
            "games_played": rs.get("GP"),
            "goals": rs.get("G"),
            "assists": rs.get("A"),
            "points": rs.get("PTS"),
            "pim": rs.get("PIM"),
        })
    return rows


# ── poll loop ──────────────────────────────────────────────────────────────
def poll_once(conn) -> int:
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT id::text, type, name, ep_player_id FROM ep_lookup_requests "
            "WHERE status = 'PENDING' ORDER BY created_at LIMIT 5")
        reqs = cur.fetchall()

    for r in reqs:
        req_id = r["id"]
        rtype = r["type"]
        name = r["name"]
        ep_id = r["ep_player_id"]
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE ep_lookup_requests SET status='RUNNING' WHERE id=%s", (req_id,))
        conn.commit()
        try:
            if rtype == "CAREER" and ep_id:
                rows = fetch_career(ep_id)
                with conn.cursor() as cur:
                    for row in rows:
                        cur.execute(
                            "INSERT INTO ep_player_career "
                            "(id, ep_player_id, season_label, team_name, league, games_played, goals, assists, points, pim, scraped_at) "
                            "VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW()) "
                            "ON CONFLICT (ep_player_id, season_label, team_name, league) DO UPDATE SET "
                            "games_played=EXCLUDED.games_played, goals=EXCLUDED.goals, assists=EXCLUDED.assists, "
                            "points=EXCLUDED.points, pim=EXCLUDED.pim, scraped_at=EXCLUDED.scraped_at",
                            (ep_id, row["season_label"], row["team_name"], row["league"],
                             row["games_played"], row["goals"], row["assists"], row["points"], row["pim"]))
                print(f"[{req_id}] CAREER {ep_id}: {len(rows)} season(s)", flush=True)
            else:
                cands = search_ep(name or "")
                with conn.cursor() as cur:
                    for c in cands:
                        cur.execute(
                            "INSERT INTO ep_lookup_candidates "
                            "(id, request_id, ep_player_id, player_name, position, year_of_birth, latest_team, latest_league, league_experience) "
                            "VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s)",
                            (req_id, c["ep_player_id"], c["player_name"], c["position"],
                             c["year_of_birth"], c["latest_team"], c["latest_league"], c["league_experience"]))
                print(f"[{req_id}] SEARCH '{name}': {len(cands)} candidate(s)", flush=True)
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE ep_lookup_requests SET status='COMPLETED', completed_at=NOW() WHERE id=%s",
                    (req_id,))
            conn.commit()
        except Exception as e:  # noqa: BLE001
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE ep_lookup_requests SET status='FAILED', error=%s, completed_at=NOW() WHERE id=%s",
                    (str(e)[:500], req_id))
            conn.commit()
            print(f"[{req_id}] {rtype} FAILED: {e}", flush=True)
    return len(reqs)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--poll", action="store_true", help="process pending EP lookup requests")
    ap.add_argument("--search", metavar="NAME", help="print candidates for a name (no DB)")
    ap.add_argument("--career", metavar="EP_ID", help="print career rows for an EP player (no DB)")
    args = ap.parse_args()

    if args.search:
        for c in search_ep(args.search):
            print(f"{c['ep_player_id']} | {c['player_name']} | {c.get('position')} | born {c.get('year_of_birth')} | {c.get('latest_team')} | {c.get('latest_league')} | {c.get('league_experience')}")
        return

    if args.career:
        for row in fetch_career(args.career):
            print(f"{row['season_label']} | {row['team_name']} | {row['league']} | GP {row['games_played']} | {row['goals']}G {row['assists']}A {row['points']}P {row['pim']}PIM")
        return

    if args.poll:
        conn = get_conn()
        try:
            n = poll_once(conn)
            if n:
                print(f"[ep_lookup] processed {n} request(s)", flush=True)
        finally:
            conn.close()
        return

    ap.print_help()


if __name__ == "__main__":
    main()
