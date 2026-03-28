"""Daily AYHL career-stats update orchestrator.

Runs every day during the active season to:
  1. Pull current-season player IDs from the database
     (integration_imported_player_record, season_label = current season).
  2. If any player has no career skeleton yet, run reconcile_rosters_to_career_json.py
     so every player has at least an empty stub before scraping.
  3. Run scrape_player_careers.py for all current-season players to fetch
     latest career stats from atlantichockey.org.
  4. Parse the resulting ayhl-player-career.json for current-season entries.
  5. Upsert into ayhl_player_career table (one row per player+season).
  6. Compare new scraped values against the previous DB snapshot; insert any
     detected changes into ayhl_change_event.

Season logic (April – March cycle):
  - Month 4-12  → season_year = current year
  - Month 1-3   → season_year = current year – 1

Usage:
    python scripts/ayhl/daily_update.py
    python scripts/ayhl/daily_update.py --season 2025
    python scripts/ayhl/daily_update.py --dry-run
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import tempfile
from datetime import date, datetime
from typing import Any
from uuid import uuid4

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(SCRIPT_DIR, "data")
CAREER_JSON_PATH = os.path.join(DATA_DIR, "ayhl-player-career.json")

# DB column name →  JSON key in career entry
STAT_FIELD_MAP: list[tuple[str, str]] = [
    ("games_played", "Games"),
    ("goals", "Goals"),
    ("assists", "Assists"),
    ("points", "Points"),
    ("penalties", "Penalties"),
    ("pim", "PIM"),
    ("team_name", "Teams"),
    ("league_name", "League"),
]
STAT_FIELDS: frozenset[str] = frozenset(
    {"games_played", "goals", "assists", "points", "penalties", "pim"}
)


# --------------------------------------------------------------------------- #
# Helpers                                                                      #
# --------------------------------------------------------------------------- #

def current_season_year() -> int:
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1


def season_label(year: int) -> str:
    return f"{year}-{year + 1} Season"


def coerce_int(val: Any) -> int | None:
    if val is None or str(val).strip() == "":
        return None
    try:
        return int(str(val).strip())
    except (ValueError, TypeError):
        return None


def run_subprocess(cmd: list) -> None:
    print(f"\n>>> {' '.join(str(c) for c in cmd)}")
    result = subprocess.run(cmd)
    if result.returncode != 0:
        print(f"  FAILED (exit {result.returncode})", file=sys.stderr)
        sys.exit(result.returncode)


def load_career_json() -> dict:
    if not os.path.exists(CAREER_JSON_PATH):
        return {}
    try:
        with open(CAREER_JSON_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {}


def extract_season_entry(
    career_data: dict, player_id: str, current_label: str
) -> dict | None:
    player = career_data.get(str(player_id))
    if not player:
        return None
    label_norm = current_label.strip().lower()
    for entry in player.get("career", []):
        if str(entry.get("Season", "")).strip().lower() == label_norm:
            return entry
    return None


def write_player_id_csv(players: list[dict]) -> str:
    """Write a temp CSV consumable by scrape_player_careers.py."""
    tf = tempfile.NamedTemporaryFile(
        "w", delete=False, suffix=".csv", encoding="utf-8", newline=""
    )
    writer = csv.DictWriter(tf, fieldnames=["playerid", "player_name"])
    writer.writeheader()
    for p in players:
        writer.writerow(
            {"playerid": p["playerid"], "player_name": p.get("player_name", "")}
        )
    tf.close()
    return tf.name


# --------------------------------------------------------------------------- #
# Database operations                                                          #
# --------------------------------------------------------------------------- #

def ensure_tables(conn) -> None:
    """Create ayhl_player_career and ayhl_change_event if they don't exist."""
    sql = """
    CREATE TABLE IF NOT EXISTS ayhl_player_career (
        id UUID PRIMARY KEY,
        source_player_id VARCHAR(128) NOT NULL,
        player_name VARCHAR(255),
        season_label VARCHAR(32) NOT NULL,
        league_name VARCHAR(255),
        team_name VARCHAR(255),
        jersey_number VARCHAR(16),
        games_played INTEGER,
        goals INTEGER,
        assists INTEGER,
        points INTEGER,
        penalties INTEGER,
        pim INTEGER,
        last_scraped_at TIMESTAMP,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL,
        UNIQUE (source_player_id, season_label)
    );

    CREATE TABLE IF NOT EXISTS ayhl_change_event (
        id UUID PRIMARY KEY,
        source_player_id VARCHAR(128) NOT NULL,
        player_name VARCHAR(255),
        season_label VARCHAR(32) NOT NULL,
        field_name VARCHAR(128) NOT NULL,
        old_value TEXT,
        new_value TEXT,
        detected_at TIMESTAMP NOT NULL,
        event_type VARCHAR(32) NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_ayhl_career_player_season
        ON ayhl_player_career (source_player_id, season_label);

    CREATE INDEX IF NOT EXISTS idx_ayhl_change_event_detection
        ON ayhl_change_event (source_player_id, season_label, detected_at);
    """
    with conn.cursor() as cur:
        cur.execute(sql)
    conn.commit()


def fetch_current_season_players(conn, label: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT DISTINCT
                player_id,
                player_name_raw,
                birth_month,
                birth_year
            FROM ayhl_roster
            WHERE season_label = %s
              AND player_id IS NOT NULL
            ORDER BY player_id
            """,
            (label,),
        )
        rows = cur.fetchall()
    return [
        {
            "playerid": r[0],
            "player_name": r[1],
            "birth_month": r[2],
            "birth_year": r[3],
        }
        for r in rows
    ]


def get_existing_career_row(conn, player_id: str, label: str) -> dict | None:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT games_played, goals, assists, points, penalties, pim,
                   team_name, league_name
            FROM ayhl_player_career
            WHERE source_player_id = %s AND season_label = %s
            """,
            (player_id, label),
        )
        row = cur.fetchone()
    if not row:
        return None
    return {
        "games_played": row[0],
        "goals": row[1],
        "assists": row[2],
        "points": row[3],
        "penalties": row[4],
        "pim": row[5],
        "team_name": row[6],
        "league_name": row[7],
    }


def upsert_career_row(
    conn, player_id: str, player_name: str, label: str, entry: dict
) -> None:
    now = datetime.utcnow()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO ayhl_player_career (
                id, source_player_id, player_name, season_label,
                league_name, team_name, jersey_number,
                games_played, goals, assists, points, penalties, pim,
                last_scraped_at, created_at, updated_at
            ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (source_player_id, season_label) DO UPDATE SET
                player_name      = EXCLUDED.player_name,
                league_name      = EXCLUDED.league_name,
                team_name        = EXCLUDED.team_name,
                jersey_number    = EXCLUDED.jersey_number,
                games_played     = EXCLUDED.games_played,
                goals            = EXCLUDED.goals,
                assists          = EXCLUDED.assists,
                points           = EXCLUDED.points,
                penalties        = EXCLUDED.penalties,
                pim              = EXCLUDED.pim,
                last_scraped_at  = EXCLUDED.last_scraped_at,
                updated_at       = EXCLUDED.updated_at
            """,
            (
                str(uuid4()),
                player_id,
                player_name,
                label,
                entry.get("League", ""),
                entry.get("Teams", ""),
                entry.get("number", ""),
                coerce_int(entry.get("Games")),
                coerce_int(entry.get("Goals")),
                coerce_int(entry.get("Assists")),
                coerce_int(entry.get("Points")),
                coerce_int(entry.get("Penalties")),
                coerce_int(entry.get("PIM")),
                now,
                now,
                now,
            ),
        )


def record_change_events(
    conn,
    player_id: str,
    player_name: str,
    label: str,
    old_row: dict | None,
    new_entry: dict,
) -> int:
    """Detect value changes and append rows to ayhl_change_event. Returns event count."""
    now = datetime.utcnow()
    events: list[tuple[str, str, str | None, str | None]] = []

    if old_row is None:
        events.append(("NEW_SEASON", "season_label", None, label))
    else:
        for db_field, json_key in STAT_FIELD_MAP:
            old_val = old_row.get(db_field)
            if db_field in STAT_FIELDS:
                new_val = coerce_int(new_entry.get(json_key))
            else:
                new_val = (new_entry.get(json_key) or "").strip() or None

            if str(old_val or "") != str(new_val or ""):
                event_type = "STAT_CHANGE" if db_field in STAT_FIELDS else "TEAM_CHANGE"
                events.append(
                    (event_type, db_field, str(old_val) if old_val is not None else None,
                     str(new_val) if new_val is not None else None)
                )

    if not events:
        return 0

    with conn.cursor() as cur:
        for event_type, field_name, old_val, new_val in events:
            cur.execute(
                """
                INSERT INTO ayhl_change_event (
                    id, source_player_id, player_name, season_label,
                    field_name, old_value, new_value, detected_at, event_type
                ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                """,
                (
                    str(uuid4()),
                    player_id,
                    player_name,
                    label,
                    field_name,
                    old_val,
                    new_val,
                    now,
                    event_type,
                ),
            )
    return len(events)


# --------------------------------------------------------------------------- #
# Main                                                                         #
# --------------------------------------------------------------------------- #

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Daily AYHL career stats update")
    parser.add_argument("--season", type=int, default=None,
                        help="Season start year (default: auto-detect)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Scrape but do not write to database")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="myhockeystats")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    year = args.season or current_season_year()
    label = season_label(year)
    python = sys.executable

    print("=" * 70)
    print(f"DAILY AYHL UPDATE — {label}")
    print("=" * 70)

    try:
        import psycopg2  # noqa: F401
    except ModuleNotFoundError:
        print("psycopg2 required: pip install psycopg2-binary", file=sys.stderr)
        return 1

    import psycopg2

    try:
        conn = psycopg2.connect(
            host=args.host, port=args.port, dbname=args.dbname,
            user=args.user, password=args.password,
        )
    except Exception as exc:
        print(f"DB connection failed: {exc}", file=sys.stderr)
        return 1

    try:
        # Always ensure tables exist (idempotent CREATE IF NOT EXISTS)
        ensure_tables(conn)

        # ---------------------------------------------------------------- #
        # Step 1: Get current-season players from DB                        #
        # ---------------------------------------------------------------- #
        print(f"\nStep 1: Fetching current-season players for '{label}' from ayhl_roster...")
        players = fetch_current_season_players(conn, label)
        if not players:
            print(f"  No players found for '{label}' in ayhl_roster.")
            print("  Run weekly_update.py first to load current-season rosters.")
            return 0
        print(f"  Found {len(players)} players.")

        # ---------------------------------------------------------------- #
        # Step 2: Reconcile career JSON for any player missing a stub       #
        # ---------------------------------------------------------------- #
        career_data = load_career_json()
        missing = [
            p for p in players
            if extract_season_entry(career_data, p["playerid"], label) is None
        ]
        roster_csv = os.path.join(
            SCRIPT_DIR, "data", "rosters", f"{year}-ayhl-rosters.csv"
        )
        if missing and os.path.exists(roster_csv):
            print(f"\nStep 2: {len(missing)} player(s) have no career stub — reconciling...")
            run_subprocess([
                python,
                os.path.join(SCRIPT_DIR, "reconcile_rosters_to_career_json.py"),
                "--rosters", roster_csv,
                "--career-json", CAREER_JSON_PATH,
            ])
            career_data = load_career_json()
        elif missing:
            print(
                f"\nStep 2: {len(missing)} player(s) missing career stub "
                f"but roster CSV not found at {roster_csv} — skipping reconcile."
            )
        else:
            print("\nStep 2: All players have a career stub — skipping reconcile.")

        # ---------------------------------------------------------------- #
        # Step 3: Scrape current-season career stats                        #
        # ---------------------------------------------------------------- #
        print(f"\nStep 3: Scraping career stats for {len(players)} players...")
        tmp_csv = write_player_id_csv(players)
        try:
            run_subprocess([
                python,
                os.path.join(SCRIPT_DIR, "scrape_player_careers.py"),
                tmp_csv,
            ])
        finally:
            try:
                os.remove(tmp_csv)
            except OSError:
                pass

        # Reload JSON after scraping
        career_data = load_career_json()

        # ---------------------------------------------------------------- #
        # Step 4: Upsert career rows and record change events               #
        # ---------------------------------------------------------------- #
        print("\nStep 4: Syncing career data to DB and detecting changes...")
        synced = 0
        skipped = 0
        total_events = 0

        for player in players:
            pid = player["playerid"]
            name = player.get("player_name", "")
            entry = extract_season_entry(career_data, pid, label)
            if entry is None:
                skipped += 1
                continue

            if args.dry_run:
                synced += 1
                continue

            old_row = get_existing_career_row(conn, pid, label)
            upsert_career_row(conn, pid, name, label, entry)
            events = record_change_events(conn, pid, name, label, old_row, entry)
            total_events += events
            synced += 1

        if not args.dry_run:
            conn.commit()

        print(f"\n{'=' * 70}")
        print("DAILY UPDATE COMPLETE")
        print("=" * 70)
        print(f"Season:                {label}")
        print(f"Players synced:        {synced}")
        print(f"Players skipped:       {skipped}  (no career data scraped)")
        print(f"Change events logged:  {total_events}")
        if args.dry_run:
            print("(dry-run: no DB writes)")
        return 0

    except Exception as exc:
        conn.rollback()
        print(f"Daily update failed: {exc}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
