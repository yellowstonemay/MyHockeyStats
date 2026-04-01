"""Scrape per-game statistics for every AYHL player in a given season.

For each player found in the season's roster CSV, this script fetches:
    https://atlantichockey.org/playerpage.php?playerid=<id>&seasonid=<id>

and parses the "Statistics By Game" table (columns: Game ID, Date, Game Type,
League, Team For, Team Against, G, G(PP), G(SH), G(SO), A, P, PIM).

Results are:
  1. Checkpointed to  data/player_games/<season_year>-ayhl-player-games.json
  2. Upserted into    ayhl_player_games  (PostgreSQL)

Usage:
    python scripts/ayhl/scrape_player_games.py --season 2025
    python scripts/ayhl/scrape_player_games.py --season 2025 --dry-run
    python scripts/ayhl/scrape_player_games.py --season 2025 --player-id 121730

Options:
    --season        AYHL season start year (e.g. 2025 → 2025-2026 season). Auto-detected if omitted.
    --dry-run       Scrape but do not write to the database.
    --player-id     Scrape a single player only (useful for testing).
    --host/--port/--dbname/--user/--password   DB connection (all have defaults).
    --delay         Seconds to wait between requests (default: 0.5).
    --roster-csv    Override path to the roster CSV.
    --no-resume     Ignore existing checkpoint and start fresh.
"""

from __future__ import annotations

import argparse
import csv
import re
import json
import os
import sys
import time
from datetime import date, datetime
from html import unescape
from pathlib import Path
from typing import Any
from uuid import uuid4

import requests
from bs4 import BeautifulSoup

# ---------------------------------------------------------------------------
# Paths / Constants
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
GAMES_DIR = DATA_DIR / "player_games"
ROSTERS_DIR = DATA_DIR / "rosters"


GAMES_DIR.mkdir(parents=True, exist_ok=True)

PLAYER_PAGE_URL = "https://atlantichockey.org/playerpage.php"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Scrape AYHL per-game stats for all players in a season"
    )
    p.add_argument("--season", type=int,
                   help="Season start year (e.g. 2025). Auto-detected if omitted.")
    p.add_argument("--player-id", dest="player_id",
                   help="Scrape a single player ID only (for testing).")
    p.add_argument("--dry-run", action="store_true",
                   help="Scrape but skip all database writes.")
    p.add_argument("--no-resume", action="store_true",
                   help="Ignore existing checkpoint and restart from scratch.")
    p.add_argument("--checkpoint", action="store_true",
                   help="Enable full games checkpoint JSON writes while scraping. Progress JSON is always written for resume.")
    p.add_argument("--delay", type=float, default=0.5,
                   help="Seconds between HTTP requests (default: 0.5).")
    p.add_argument("--roster-csv",
                   help="Override path to the roster CSV.")
    # DB connection
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=5432)
    p.add_argument("--dbname", default="myhockeystats")
    p.add_argument("--user", default="postgres")
    p.add_argument("--password", default="postgres")
    return p.parse_args()


# ---------------------------------------------------------------------------
# Season helpers
# ---------------------------------------------------------------------------

def current_season_year() -> int:
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1


def season_label(year: int) -> str:
    return f"{year}-{year + 1} Season"


# ---------------------------------------------------------------------------
# Roster reading
# ---------------------------------------------------------------------------

def load_players_from_roster_csv(csv_path: str) -> tuple[str | None, list[dict[str, str]]]:
    """Return (season_id, players) where players are {player_id, player_name} dicts.

    season_id is read from the 'seasonid' column of the first data row.
    Players are deduplicated by player_id.
    """
    seen: set[str] = set()
    players: list[dict[str, str]] = []
    season_id: str | None = None
    with open(csv_path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            if season_id is None:
                season_id = row.get("seasonid", "").strip() or None
            pid = row.get("playerid", "").strip()
            if not pid or pid in seen:
                continue
            # player column is "Last,First" – normalise to "First Last"
            raw_name = row.get("player", "").strip().strip('"')
            if "," in raw_name:
                last, first = raw_name.split(",", 1)
                player_name = f"{first.strip()} {last.strip()}"
            else:
                player_name = raw_name
            seen.add(pid)
            players.append({"player_id": pid, "player_name": player_name})
    return season_id, players


# ---------------------------------------------------------------------------
# HTTP fetch
# ---------------------------------------------------------------------------

_SESSION: requests.Session | None = None


def _get_session() -> requests.Session:
    global _SESSION
    if _SESSION is None:
        _SESSION = requests.Session()
        _SESSION.headers.update({
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0.0.0 Safari/537.36"
            )
        })
    return _SESSION


def fetch_player_page(player_id: str, season_id: int, timeout: int = 20) -> str | None:
    url = PLAYER_PAGE_URL
    params = {"playerid": player_id, "seasonid": str(season_id)}
    try:
        resp = _get_session().get(url, params=params, timeout=timeout)
        resp.raise_for_status()
        return resp.text
    except requests.RequestException as exc:
        print(f"  [WARN] HTTP error for player {player_id}: {exc}")
        return None


# ---------------------------------------------------------------------------
# HTML parsing
# ---------------------------------------------------------------------------

def _safe_int(val: str) -> int | None:
    v = val.strip()
    if not v or v == "-":
        return None
    try:
        return int(v)
    except ValueError:
        return None


def _parse_date(val: str) -> str | None:
    """Convert MM/DD/YY to ISO date string YYYY-MM-DD, or None on failure."""
    v = val.strip()
    if not v:
        return None
    try:
        parts = v.split("/")
        if len(parts) == 3:
            month, day, year_2 = int(parts[0]), int(parts[1]), int(parts[2])
            # two-digit year: 00-99  → 2000-2099
            full_year = 2000 + year_2
            return f"{full_year:04d}-{month:02d}-{day:02d}"
    except (ValueError, IndexError):
        pass
    return None


def _strip_tags(value: str) -> str:
    text = re.sub(r"<[^>]+>", "", value)
    return unescape(text).replace("\xa0", " ").strip()


def parse_games_table(html: str, player_id: str, player_name: str,
                      season_year: int, season_id: int) -> list[dict[str, Any]]:
    """
    Parse the "Statistics By Game" table from a player page.

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

    # Required columns
    required = {"gameid", "date", "teamfor", "teamagainst", "g", "a", "p", "pim"}
    if not required.issubset(col_map):
        print(f"  [WARN] Unexpected table columns for player {player_id}: {list(col_map.keys())}")
        return []

    games: list[dict[str, Any]] = []
    scraped_at = datetime.utcnow().isoformat()
    label = season_label(season_year)

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


# ---------------------------------------------------------------------------
# Checkpoint (JSON)
# ---------------------------------------------------------------------------

def checkpoint_path(season_year: int) -> Path:
    return GAMES_DIR / f"{season_year}-ayhl-player-games.json"


def checkpoint_progress_path(season_year: int) -> Path:
    return GAMES_DIR / f"{season_year}-ayhl-player-games-progress.json"


def load_checkpoint(season_year: int) -> dict[str, list[dict]]:
    """Load existing saved games keyed by player_id."""
    path = checkpoint_path(season_year)
    if not path.exists():
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
            if isinstance(data, dict):
                return data
    except Exception:
        pass
    return {}


def save_checkpoint(season_year: int, data: dict[str, list[dict]]) -> None:
    path = checkpoint_path(season_year)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, default=str)


def load_progress(season_year: int) -> set[str]:
    """Return set of completed player IDs from progress file."""
    path = checkpoint_progress_path(season_year)
    if not path.exists():
        return set()
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
            return set(data.get("completed", []))
    except Exception:
        return set()


def save_progress(season_year: int, completed: set[str]) -> None:
    path = checkpoint_progress_path(season_year)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump({"completed": sorted(completed)}, fh, indent=2)


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

UPSERT_SQL = """
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

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS ayhl_player_games (
    id              UUID            PRIMARY KEY,
    player_id       VARCHAR(128)    NOT NULL,
    player_name     VARCHAR(255),
    season_year     INTEGER         NOT NULL,
    season_label    VARCHAR(32)     NOT NULL,
    season_id       VARCHAR(32)     NOT NULL,
    game_id         VARCHAR(32)     NOT NULL,
    game_date       DATE,
    game_type       VARCHAR(128),
    league          VARCHAR(255),
    team_for        VARCHAR(255),
    team_against    VARCHAR(255),
    goals           INTEGER,
    goals_pp        INTEGER,
    goals_sh        INTEGER,
    goals_so        INTEGER,
    assists         INTEGER,
    points          INTEGER,
    pim             INTEGER,
    scraped_at      TIMESTAMP       NOT NULL,
    UNIQUE (player_id, season_year, game_id)
);
CREATE INDEX IF NOT EXISTS idx_ayhl_player_games_player_season
    ON ayhl_player_games (player_id, season_year);
CREATE INDEX IF NOT EXISTS idx_ayhl_player_games_season
    ON ayhl_player_games (season_year);
"""


def get_db_connection(args: argparse.Namespace):
    import psycopg2
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.dbname,
        user=args.user,
        password=args.password,
    )


def ensure_table(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(CREATE_TABLE_SQL)
    conn.commit()


def upsert_player_games(conn, games: list[dict[str, Any]]) -> int:
    with conn.cursor() as cur:
        for g in games:
            cur.execute(UPSERT_SQL, (
                str(uuid4()),
                g["player_id"],
                g["player_name"],
                g["season_year"],
                g["season_label"],
                g["season_id"],
                g["game_id"],
                g["game_date"],
                g["game_type"],
                g["league"],
                g["team_for"],
                g["team_against"],
                g["goals"],
                g["goals_pp"],
                g["goals_sh"],
                g["goals_so"],
                g["assists"],
                g["points"],
                g["pim"],
                g["scraped_at"],
            ))
    conn.commit()
    return len(games)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = parse_args()

    season_year = args.season if args.season else current_season_year()
    label = season_label(season_year)

    # ---- Resolve roster CSV ------------------------------------------------
    if args.roster_csv:
        roster_csv = args.roster_csv
    else:
        roster_csv = str(ROSTERS_DIR / f"{season_year}-ayhl-rosters.csv")

    if not os.path.exists(roster_csv):
        print(f"[ERROR] Roster CSV not found: {roster_csv}")
        print(f"Run:  python scripts/ayhl/weekly_update.py --season {season_year}")
        sys.exit(1)

    # ---- Build player list + read season_id from CSV -----------------------
    season_id_str, all_players = load_players_from_roster_csv(roster_csv)
    if not season_id_str:
        print(f"[ERROR] Could not read seasonid from roster CSV: {roster_csv}")
        sys.exit(1)
    season_id = int(season_id_str)

    print(f"Season: {label}  (season_id={season_id})")

    if args.player_id:
        all_players = [p for p in all_players if p["player_id"] == args.player_id]
        if not all_players:
            # Allow scraping a player not in the CSV (manual override)
            all_players = [{"player_id": args.player_id, "player_name": ""}]

    print(f"Players to scrape: {len(all_players)}")

    game_checkpoint_enabled = args.checkpoint or args.dry_run
    if not game_checkpoint_enabled:
        print("Games checkpoint disabled: writing game data directly to database only.")
    print("Progress checkpoint enabled for resume.")

    # ---- Load existing checkpoint -------------------------------------------
    if game_checkpoint_enabled:
        checkpoint: dict[str, list[dict]] = {} if args.no_resume else load_checkpoint(season_year)
    else:
        checkpoint = {}
    completed: set[str] = set() if args.no_resume else load_progress(season_year)

    # ---- Database setup (unless dry-run) ------------------------------------
    conn = None
    if not args.dry_run:
        try:
            conn = get_db_connection(args)
            ensure_table(conn)
            print("Database connection established.")
        except Exception as exc:
            print(f"[ERROR] Could not connect to database: {exc}")
            sys.exit(1)

    # ---- Scrape loop --------------------------------------------------------
    total_upserted = 0
    total_games = 0

    for idx, player in enumerate(all_players, start=1):
        pid = player["player_id"]
        pname = player["player_name"]

        if pid in completed:
            print(f"  [{idx}/{len(all_players)}] Skip (already done): {pid} {pname}")
            continue

        print(f"  [{idx}/{len(all_players)}] Scraping: {pid} {pname} ...", end=" ", flush=True)
        html = fetch_player_page(pid, season_id)
        if html is None:
            print("FAILED (HTTP error)")
            continue

        games = parse_games_table(html, pid, pname, season_year, season_id)
        print(f"{len(games)} games")

        if game_checkpoint_enabled:
            checkpoint[pid] = games
        total_games += len(games)

        if conn and games:
            try:
                upserted = upsert_player_games(conn, games)
                total_upserted += upserted
            except Exception as exc:
                print(f"  [WARN] DB upsert failed for {pid}: {exc}")
                conn.rollback()

        completed.add(pid)

        if game_checkpoint_enabled:
            # Persist game checkpoint after each player
            save_checkpoint(season_year, checkpoint)
        save_progress(season_year, completed)

        if args.delay > 0 and idx < len(all_players):
            time.sleep(args.delay)

    # ---- Summary ------------------------------------------------------------
    print(f"\nDone. Players scraped: {len(completed)}  "
          f"Total games collected: {total_games}  "
          f"DB rows upserted: {total_upserted}")
    if game_checkpoint_enabled:
        print(f"Checkpoint: {checkpoint_path(season_year)}")
    else:
        print("Checkpoint: disabled")
    print(f"Progress: {checkpoint_progress_path(season_year)}")

    if conn:
        conn.close()


if __name__ == "__main__":
    main()
