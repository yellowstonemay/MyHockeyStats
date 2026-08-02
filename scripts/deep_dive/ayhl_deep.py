#!/usr/bin/env python3
"""AYHL deep-dive for registered users.

For each AYHL identity link (or a single --player-id), fetch the player's FULL
career from atlantichockey.org (all seasons, not just current), upsert every
season into `ayhl_player_career`, record any stat changes into
`ayhl_change_event` (per-field old -> new), and upsert the player's game-by-game
history into `ayhl_player_games` (current season by default; every season with
--all-seasons). Game rows are UPSERTed on (player_id, season_year, game_id) —
existing rows are never deleted.

The AYHL player page (playerpage.php?playerid=...) is server-rendered HTML, so
plain HTTP + BeautifulSoup is sufficient (no browser needed).

Usage:
    python ayhl_deep.py                       # all AYHL-linked users
    python ayhl_deep.py --player-id 125307    # single player
    python ayhl_deep.py --all-seasons         # career + games for all seasons
    python ayhl_deep.py --dry-run             # fetch + show, no DB writes
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone, date
from html import unescape
from uuid import uuid4

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


def current_season_year() -> int:
    """Current AYHL season start year (April–March cycle)."""
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1


def season_label_for(year: int) -> str:
    return f"{year}-{year + 1} Season"


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


def fetch_player_html(pid: str, season_id: int | None = None) -> str:
    """Fetch a player page's raw HTML, preferring playwright+stealth (required on
    the Mac mini where plain requests gets a Cloudflare 403), falling back to
    requests. Passing season_id returns that season's game table."""
    url = BASE_URL.format(pid=pid)
    if season_id is not None:
        url += f"&seasonid={season_id}"
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
        return html
    except Exception as e:
        print(f"    (playwright failed: {e}; trying requests)")
        resp = requests.get(url, headers=HEADERS, timeout=30)
        resp.raise_for_status()
        return resp.text


def fetch_player_page(pid: str, season_id: int | None = None) -> BeautifulSoup:
    return BeautifulSoup(fetch_player_html(pid, season_id), "html.parser")


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


def upsert_career(conn, entry: dict, all_seasons: bool = False) -> list[dict]:
    """Upsert one player's career rows; return list of change events."""
    changes: list[dict] = []
    pid = entry["pid"]
    name = entry["name"] or "N/A"
    jersey = entry["jersey"] or None
    now = datetime.now(timezone.utc)

    rows = entry["rows"]
    if not all_seasons:
        cur_label = season_label_for(current_season_year())
        rows = [r for r in rows if r[0] == cur_label]

    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        for cells in rows:
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


# ---------------------------------------------------------------------------
# Game-by-game history (upsert into ayhl_player_games — never deletes rows)
# ---------------------------------------------------------------------------

def _safe_int(val: str) -> int | None:
    v = (val or "").strip()
    if not v or v == "-":
        return None
    try:
        return int(v)
    except ValueError:
        return None


def _parse_date(val: str) -> str | None:
    """Convert MM/DD/YY to ISO date string YYYY-MM-DD, or None on failure."""
    v = (val or "").strip()
    if not v:
        return None
    try:
        parts = v.split("/")
        if len(parts) == 3:
            month, day, year_2 = int(parts[0]), int(parts[1]), int(parts[2])
            full_year = 2000 + year_2
            return f"{full_year:04d}-{month:02d}-{day:02d}"
    except (ValueError, IndexError):
        pass
    return None


def _strip_tags(value: str) -> str:
    text = re.sub(r"<[^>]+>", "", value)
    return unescape(text).replace("\xa0", " ").strip()


def parse_games_table(html: str, player_id: str, player_name: str,
                      season_year: int, season_id: int | str) -> list[dict]:
    """Parse the 'Statistics By Game' table from a player page.

    Expected columns (in order):
        Game ID | Date | Game Type | League | Team For | Team Against |
        G | G(PP) | G(SH) | G(SO) | A | P | PIM
    """
    heading_match = re.search(
        r"Statistics By Game</h[234]>\s*<table class=\"w3-table\">\s*<tr><td>\s*<table[^>]*>(.*?)</table>",
        html,
        re.IGNORECASE | re.DOTALL,
    )
    if not heading_match:
        return []

    table_html = heading_match.group(1)
    row_matches = re.findall(r"<tr\b[^>]*>(.*?)</tr>", table_html, re.IGNORECASE | re.DOTALL)
    if not row_matches:
        return []

    header_cells_raw = re.findall(r"<th\b[^>]*>(.*?)</(?:th|td)>", row_matches[0], re.IGNORECASE | re.DOTALL)
    header_cells = [_strip_tags(cell) for cell in header_cells_raw]
    normalised = [c.lower().replace(" ", "").replace("(", "").replace(")", "") for c in header_cells]
    col_map = {name: idx for idx, name in enumerate(normalised)}

    if "gameid" not in col_map:
        return []

    required = {"gameid", "date", "teamfor", "teamagainst", "g", "a", "p", "pim"}
    if not required.issubset(col_map):
        print(f"    [WARN] Unexpected game table columns for player {player_id}: {list(col_map.keys())}")
        return []

    games: list[dict] = []
    scraped_at = datetime.now(timezone.utc)
    label = season_label_for(season_year)

    for row_html in row_matches[1:]:
        cells = [_strip_tags(cell) for cell in re.findall(r"<td\b[^>]*>(.*?)</td>", row_html, re.IGNORECASE | re.DOTALL)]
        if len(cells) < len(col_map):
            continue

        def col(name: str) -> str:
            idx = col_map.get(name)
            return cells[idx].strip() if idx is not None and idx < len(cells) else ""

        game_id = col("gameid")
        if not game_id:
            continue

        games.append({
            "player_id": player_id,
            "player_name": player_name,
            "season_year": season_year,
            "season_label": label,
            "season_id": str(season_id),
            "game_id": game_id,
            "game_date": _parse_date(col("date")),
            "game_type": col("gametype") or None,
            "league": col("league") or None,
            "team_for": col("teamfor") or None,
            "team_against": col("teamagainst") or None,
            "goals": _safe_int(col("g")),
            "goals_pp": _safe_int(col("gpp")),
            "goals_sh": _safe_int(col("gsh")),
            "goals_so": _safe_int(col("gso")),
            "assists": _safe_int(col("a")),
            "points": _safe_int(col("p")),
            "pim": _safe_int(col("pim")),
            "scraped_at": scraped_at,
        })

    return games


UPSERT_GAMES_SQL = """
INSERT INTO ayhl_player_games (
    id, player_id, player_name, season_year, season_label, season_id,
    game_id, game_date, game_type, league,
    team_for, team_against,
    goals, goals_pp, goals_sh, goals_so,
    assists, points, pim, scraped_at
) VALUES (
    %s, %s, %s, %s, %s, %s,
    %s, %s, %s, %s,
    %s, %s,
    %s, %s, %s, %s,
    %s, %s, %s, %s
)
ON CONFLICT (player_id, season_year, game_id) DO UPDATE SET
    player_name   = EXCLUDED.player_name,
    season_label  = EXCLUDED.season_label,
    season_id     = EXCLUDED.season_id,
    game_date     = EXCLUDED.game_date,
    game_type     = EXCLUDED.game_type,
    league        = EXCLUDED.league,
    team_for      = EXCLUDED.team_for,
    team_against  = EXCLUDED.team_against,
    goals         = EXCLUDED.goals,
    goals_pp      = EXCLUDED.goals_pp,
    goals_sh      = EXCLUDED.goals_sh,
    goals_so      = EXCLUDED.goals_so,
    assists       = EXCLUDED.assists,
    points        = EXCLUDED.points,
    pim           = EXCLUDED.pim,
    scraped_at    = EXCLUDED.scraped_at
"""


def upsert_player_games(conn, games: list[dict]) -> int:
    """Upsert game rows into ayhl_player_games. Existing rows (same player_id +
    season_year + game_id) are UPDATEd, never deleted."""
    with conn.cursor() as cur:
        for g in games:
            cur.execute(UPSERT_GAMES_SQL, (
                str(uuid4()),
                g["player_id"], g["player_name"], g["season_year"], g["season_label"],
                g["season_id"], g["game_id"], g["game_date"], g["game_type"], g["league"],
                g["team_for"], g["team_against"],
                g["goals"], g["goals_pp"], g["goals_sh"], g["goals_so"],
                g["assists"], g["points"], g["pim"], g["scraped_at"],
            ))
    conn.commit()
    return len(games)


def _roster_season_ids(conn) -> list[tuple[int, int]]:
    """(season_year, season_id) pairs from ayhl_roster, newest first."""
    with conn.cursor() as cur:
        cur.execute("SELECT DISTINCT season_id, season_label FROM ayhl_roster")
        out: list[tuple[int, int]] = []
        for sid, label in cur.fetchall():
            m = re.match(r"(\d{4})-", label or "")
            if m:
                try:
                    out.append((int(m.group(1)), int(sid)))
                except (TypeError, ValueError):
                    pass
    return sorted(out, key=lambda x: -x[0])


def game_history(conn, pid: str, name: str, all_seasons: bool = False,
                 dry_run: bool = False) -> int:
    """Fetch + upsert game-by-game history for one AYHL player.

    The AYHL player page only renders its "Statistics By Game" table when given
    a seasonid, so we look the season ids up from ayhl_roster and fetch with
    them. Default: the most recent season that has games (fallback: the current
    season). --all-seasons: every season in ayhl_roster. Rows are UPSERTed on
    (player_id, season_year, game_id) — existing rows are refreshed, never
    deleted.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT COALESCE(MAX(season_year), 0) FROM ayhl_player_games")
        max_game_year = cur.fetchone()[0] or 0
    seasons = _roster_season_ids(conn)

    if all_seasons:
        targets = seasons
    else:
        want = max_game_year if max_game_year else current_season_year()
        targets = [(sy, sid) for sy, sid in seasons if sy == want]
        if not targets and seasons:
            targets = seasons[:1]

    total = 0
    for season_year, sid in targets:
        try:
            html = fetch_player_html(pid, season_id=sid)
        except Exception as e:
            print(f"    games[{season_year}-{season_year + 1}]: ERROR {e}")
            continue
        games = parse_games_table(html, pid, name or "N/A", season_year, sid)
        if not games:
            print(f"    games[{season_year}-{season_year + 1}]: no game rows")
            continue
        if dry_run:
            print(f"    games[{season_year}-{season_year + 1}]: {len(games)} row(s) (dry-run)")
            total += len(games)
        else:
            upsert_player_games(conn, games)
            print(f"    games[{season_year}-{season_year + 1}]: upserted {len(games)} row(s)")
            total += len(games)
    return total


def create_skeletons(conn, pid: str, name: str, all_seasons: bool = False) -> int:
    """For a player who appears in ayhl_roster but has NO career record for
    those seasons, create 'skeleton' season rows with empty stats and
    is_user_modified = TRUE so the user can fill them in from the GUI.
    By default only the current season is considered."""
    with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
        cur.execute(
            "SELECT DISTINCT season_label, team_name, jersey_number, player_name_raw "
            "FROM ayhl_roster WHERE player_id = %s ORDER BY season_label",
            (pid,),
        )
        roster_rows = [dict(r) for r in cur.fetchall()]
        if not roster_rows:
            return 0
        if not all_seasons:
            cur_label = season_label_for(current_season_year())
            roster_rows = [r for r in roster_rows if r["season_label"] == cur_label]
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
    ap.add_argument("--all-seasons", action="store_true",
                    help="process ALL seasons (default: current season only)")
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
                changes = upsert_career(conn, entry, all_seasons=args.all_seasons)
                skeletons = create_skeletons(conn, pid, entry["name"], all_seasons=args.all_seasons)
                if changes:
                    print(f"    -> {len(changes)} change(s) detected & logged")
                else:
                    print("    -> no stat changes")
                if skeletons:
                    print(f"    -> created {skeletons} skeleton season(s) (empty stats, user-editable)")
                # Game-by-game history (upsert into ayhl_player_games)
                game_history(conn, pid, entry["name"],
                             all_seasons=args.all_seasons, dry_run=args.dry_run)
            except Exception as e:
                print(f"  {pid}: ERROR {e}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
