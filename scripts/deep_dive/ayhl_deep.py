#!/usr/bin/env python3
"""AYHL deep-dive for registered users.

For each AYHL identity link (or a single --player-id), fetch the player's FULL
career from atlantichockey.org (all seasons, not just current), upsert every
season into `ayhl_player_career`, and record any stat changes into
`ayhl_change_event` (per-field old -> new).

The AYHL player page (playerpage.php?playerid=...) is server-rendered HTML, so
plain HTTP + BeautifulSoup is sufficient (no browser needed).

Usage:
    python ayhl_deep.py                       # all AYHL-linked users
    python ayhl_deep.py --player-id 125307    # single player
    python ayhl_deep.py --dry-run             # fetch + show, no DB writes
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone

import psycopg2
import psycopg2.extras
import requests
from bs4 import BeautifulSoup

BASE_URL = "https://atlantichockey.org/playerpage.php?playerid={pid}"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
}

STAT_FIELDS = [
    ("games_played", "Games"),
    ("goals", "Goals"),
    ("assists", "Assists"),
    ("points", "Points"),
    ("penalties", "Penalties"),
    ("pim", "PIM"),
]


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "5432")),
        dbname=os.environ.get("DB_NAME", "hockey_stats"),
        user=os.environ.get("DB_USER", "admin"),
        password=os.environ.get("DB_PASSWORD", "your_secure_password"),
    )


def load_ayhl_linked_players(conn) -> list[tuple[str, str]]:
    """Return [(source_player_id, player_name_or_empty)] from identity map."""
    sql = """
        SELECT pim.source_player_id, pp.full_name
        FROM player_identity_map pim
        LEFT JOIN player_profiles pp ON pp.user_id = pim.user_id
        WHERE pim.source = 'AYHL' AND pim.link_state = 'CONFIRMED'
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    # Dedupe by player id (multiple users can link to the same source player)
    seen = {}
    for pid, name in rows:
        if pid not in seen:
            seen[pid] = name or ""
    return list(seen.items())


def fetch_player_page(pid: str) -> BeautifulSoup:
    """Fetch a player page, preferring playwright+stealth (required on the Mac
    mini where plain requests gets a Cloudflare 403), falling back to requests."""
    url = BASE_URL.format(pid=pid)
    try:
        from playwright.sync_api import sync_playwright
        from playwright_stealth import Stealth
        _stealth = Stealth()
        with sync_playwright() as p:
            browser = p.chromium.launch(
                headless=True,
                args=["--disable-blink-features=AutomationControlled", "--no-sandbox"],
            )
            try:
                page = browser.new_page()
                page.set_default_navigation_timeout(45000)
                _stealth.apply_stealth_sync(page)
                page.goto(url, wait_until="load", timeout=45000)
                # Wait out a Cloudflare JS challenge ("Just a moment...") — real
                # browsers auto-resolve it after a few seconds.
                for _ in range(10):
                    title = (page.title() or "").lower()
                    if "just a moment" not in title:
                        break
                    page.wait_for_timeout(2500)
                page.wait_for_timeout(1500)
                html = page.content()
            finally:
                browser.close()
        return BeautifulSoup(html, "html.parser")
    except Exception as e:
        print(f"    (playwright failed: {e}; trying requests)")
        resp = requests.get(url, headers=HEADERS, timeout=30)
        resp.raise_for_status()
        return BeautifulSoup(resp.text, "html.parser")


def parse_player(soup: BeautifulSoup, pid: str) -> dict:
    """Extract name, jersey, career rows, and current-season game rows."""
    name = ""
    jersey = ""
    # The page has a title h1 ("AYHL Player Bio") plus a player heading like
    # "#6  Ethan Yan" (jersey optional). Skip the page title.
    for h in soup.find_all("h1"):
        txt = h.get_text(" ", strip=True)
        low = txt.lower()
        if low.startswith("ayhl") or "player bio" in low:
            continue
        jm = re.match(r"#\s*(\d+)", txt)
        if jm:
            jersey = jm.group(1)
        name = re.sub(r"#\s*\d+", "", txt).strip()
        break

    # Career rows: only from the main career table (w3-table), exactly 9 cells,
    # and a clean "YYYY-YYYY Season" in the first cell. This avoids the nested
    # outer table rows and duplicates.
    rows: list[list[str]] = []
    seen = set()
    for table in soup.find_all("table", class_="w3-table"):
        for tr in table.find_all("tr"):
            cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
            if len(cells) == 9 and re.fullmatch(r"\d{4}-\d{4} Season", cells[0] or ""):
                if cells[0] not in seen:
                    seen.add(cells[0])
                    rows.append(cells)

    # Current-season game-by-game rows (second w3-table). The header row has
    # <th> so it yields 0 <td>; a "played no games" message yields 0 data rows.
    game_rows: list[list[str]] = []
    for table in soup.find_all("table", class_="w3-table"):
        for tr in table.find_all("tr"):
            cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
            if cells and not re.fullmatch(r"\d{4}-\d{4} Season", cells[0] or ""):
                game_rows.append(cells)

    return {"pid": pid, "name": name, "jersey": jersey,
            "rows": rows, "game_rows": game_rows}


def to_int(v) -> int | None:
    try:
        return int(str(v or "").strip())
    except (TypeError, ValueError):
        return None


def upsert_career(conn, entry: dict) -> list[dict]:
    """Upsert one player's career rows; return list of change events."""
    changes: list[dict] = []
    pid = entry["pid"]
    name = entry["name"] or "N/A"
    jersey = entry["jersey"] or None
    now = datetime.now(timezone.utc)

    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        for cells in entry["rows"]:
            season_label = cells[0]
            league_name = "AYHL"
            team_name = cells[2] if len(cells) > 2 else None
            vals = {
                "games_played": to_int(cells[3]) if len(cells) > 3 else None,
                "goals": to_int(cells[4]) if len(cells) > 4 else None,
                "assists": to_int(cells[5]) if len(cells) > 5 else None,
                "points": to_int(cells[6]) if len(cells) > 6 else None,
                "penalties": to_int(cells[7]) if len(cells) > 7 else None,
                "pim": to_int(cells[8]) if len(cells) > 8 else None,
            }

            # Fetch existing row for change detection
            cur.execute(
                "SELECT games_played, goals, assists, points, penalties, pim "
                "FROM ayhl_player_career WHERE source_player_id=%s AND season_label=%s",
                (pid, season_label),
            )
            old = cur.fetchone()

            if old:
                for col in STAT_FIELDS:
                    o, n = old[col[0]], vals[col[0]]
                    if (o or 0) != (n or 0):
                        changes.append({
                            "source_player_id": pid,
                            "player_name": name,
                            "season_label": season_label,
                            "field_name": col[0],
                            "old_value": str(o) if o is not None else "",
                            "new_value": str(n) if n is not None else "",
                        })

            cur.execute(
                """INSERT INTO ayhl_player_career
                   (id, source_player_id, player_name, season_label, league_name,
                    team_name, jersey_number, games_played, goals, assists, points,
                    penalties, pim, last_scraped_at, created_at, updated_at)
                   VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
                   ON CONFLICT (source_player_id, season_label) DO UPDATE SET
                     player_name = EXCLUDED.player_name,
                     league_name = EXCLUDED.league_name,
                     team_name = EXCLUDED.team_name,
                     jersey_number = EXCLUDED.jersey_number,
                     games_played = EXCLUDED.games_played,
                     goals = EXCLUDED.goals,
                     assists = EXCLUDED.assists,
                     points = EXCLUDED.points,
                     penalties = EXCLUDED.penalties,
                     pim = EXCLUDED.pim,
                     last_scraped_at = EXCLUDED.last_scraped_at,
                     updated_at = NOW()""",
                (pid, name, season_label, league_name, team_name, jersey,
                 vals["games_played"], vals["goals"], vals["assists"], vals["points"],
                 vals["penalties"], vals["pim"], now),
            )

        for ch in changes:
            cur.execute(
                """INSERT INTO ayhl_change_event
                   (id, source_player_id, player_name, season_label, field_name,
                    old_value, new_value, detected_at, event_type)
                   VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, NOW(), 'STAT_CHANGE')""",
                (ch["source_player_id"], ch["player_name"], ch["season_label"],
                 ch["field_name"], ch["old_value"], ch["new_value"]),
            )
    conn.commit()
    return changes


def create_skeletons(conn, pid: str, name: str) -> int:
    """For a player who appears in ayhl_roster but has NO career record for
    those seasons, create 'skeleton' season rows with empty stats and
    is_user_modified = TRUE so the user can fill them in from the GUI."""
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT DISTINCT season_label, team_name, jersey_number, player_name_raw "
            "FROM ayhl_roster WHERE player_id = %s ORDER BY season_label",
            (pid,),
        )
        roster_rows = [dict(r) for r in cur.fetchall()]
        if not roster_rows:
            return 0
        cur.execute(
            "SELECT season_label FROM ayhl_player_career WHERE source_player_id = %s",
            (pid,),
        )
        existing = {r[0] for r in cur.fetchall()}
        created = 0
        for row in roster_rows:
            label = row["season_label"]
            if label in existing:
                continue
            player_name = name or (row["player_name_raw"] or "") or "N/A"
            cur.execute(
                """INSERT INTO ayhl_player_career
                   (id, source_player_id, player_name, season_label, league_name,
                    team_name, jersey_number, games_played, goals, assists, points,
                    penalties, pim, is_user_modified, last_scraped_at, created_at, updated_at)
                   VALUES (gen_random_uuid(), %s, %s, %s, 'AYHL', %s, %s,
                           0, 0, 0, 0, 0, 0, TRUE, NOW(), NOW(), NOW())
                   ON CONFLICT (source_player_id, season_label) DO NOTHING""",
                (pid, player_name, label, row["team_name"], row["jersey_number"]),
            )
            created += 1
        conn.commit()
        return created


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--player-id", help="scrape a single player id")
    ap.add_argument("--dry-run", action="store_true", help="fetch + show, no DB writes")
    args = ap.parse_args()

    conn = get_conn()
    try:
        if args.player_id:
            players = [(args.player_id, "")]
        else:
            players = load_ayhl_linked_players(conn)
        if not players:
            print("No AYHL-linked users to deep-dive.")
            return
        print(f"Deep-diving {len(players)} AYHL player(s)...")
        for pid, _label in players:
            try:
                soup = fetch_player_page(pid)
                entry = parse_player(soup, pid)
                print(f"  {pid}: {entry['name'] or '(no name)'} jersey={entry['jersey'] or '?'} "
                      f"seasons={len(entry['rows'])} gameRows={len(entry['game_rows'])}")
                if not entry["rows"]:
                    print("    (no career table on source page)")
                if args.dry_run:
                    for cells in entry["rows"]:
                        print("    ", cells)
                    continue
                changes = upsert_career(conn, entry)
                skeletons = create_skeletons(conn, pid, entry["name"])
                if changes:
                    print(f"    -> {len(changes)} change(s) detected & logged")
                else:
                    print("    -> no stat changes")
                if skeletons:
                    print(f"    -> created {skeletons} skeleton season(s) (empty stats, user-editable)")
            except Exception as e:
                print(f"  {pid}: ERROR {e}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
