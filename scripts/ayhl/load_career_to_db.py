"""Bulk-load all historical AYHL player career data into ayhl_player_career.

Reads scripts/ayhl/data/ayhl-player-career.json, iterates every player and
every season entry, and upserts into the ayhl_player_career table.

Empty player entries (no career list) and entries with blank stats are
written with NULL numeric values — they are still upserted so the row
exists for future scrape updates.

Usage:
    python scripts/ayhl/load_career_to_db.py
    python scripts/ayhl/load_career_to_db.py --dry-run
    python scripts/ayhl/load_career_to_db.py --host localhost --port 5432
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from typing import Any
from uuid import uuid4

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CAREER_JSON_PATH = os.path.join(SCRIPT_DIR, "data", "ayhl-player-career.json")


def coerce_int(val: Any) -> int | None:
    if val is None or str(val).strip() == "":
        return None
    try:
        return int(str(val).strip())
    except (ValueError, TypeError):
        return None


def ensure_table(conn) -> None:
    with conn.cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS ayhl_player_career (
                id               UUID         PRIMARY KEY,
                source_player_id VARCHAR(128) NOT NULL,
                player_name      VARCHAR(255),
                season_label     VARCHAR(32)  NOT NULL,
                league_name      VARCHAR(255),
                team_name        VARCHAR(255),
                jersey_number    VARCHAR(16),
                games_played     INTEGER,
                goals            INTEGER,
                assists          INTEGER,
                points           INTEGER,
                penalties        INTEGER,
                pim              INTEGER,
                last_scraped_at  TIMESTAMP,
                created_at       TIMESTAMP    NOT NULL,
                updated_at       TIMESTAMP    NOT NULL,
                UNIQUE (source_player_id, season_label)
            );
            CREATE INDEX IF NOT EXISTS idx_ayhl_career_player_season
                ON ayhl_player_career (source_player_id, season_label);
        """)
    conn.commit()


def upsert_row(conn, player_id: str, player_name: str, entry: dict, now: datetime) -> None:
    label = str(entry.get("Season", "")).strip()
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
                player_name     = EXCLUDED.player_name,
                league_name     = EXCLUDED.league_name,
                team_name       = EXCLUDED.team_name,
                jersey_number   = EXCLUDED.jersey_number,
                games_played    = EXCLUDED.games_played,
                goals           = EXCLUDED.goals,
                assists         = EXCLUDED.assists,
                points          = EXCLUDED.points,
                penalties       = EXCLUDED.penalties,
                pim             = EXCLUDED.pim,
                last_scraped_at = EXCLUDED.last_scraped_at,
                updated_at      = EXCLUDED.updated_at
            """,
            (
                str(uuid4()),
                player_id,
                player_name,
                label,
                (entry.get("League") or "").strip() or None,
                (entry.get("Teams") or "").strip() or None,
                (entry.get("number") or "").strip() or None,
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Bulk-load ayhl-player-career.json into ayhl_player_career"
    )
    parser.add_argument("--career-json", default=CAREER_JSON_PATH,
                        help="Path to ayhl-player-career.json")
    parser.add_argument("--dry-run", action="store_true",
                        help="Parse and count without writing to DB")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="myhockeystats")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    career_json_path = args.career_json

    print("=" * 70)
    print("AYHL CAREER BULK LOAD")
    print("=" * 70)
    print(f"Source: {career_json_path}")

    if not os.path.exists(career_json_path):
        print(f"ERROR: {career_json_path} not found", file=sys.stderr)
        return 1

    print("Loading JSON (this may take a moment for large files)...")
    with open(career_json_path, "r", encoding="utf-8") as fh:
        career_data: dict = json.load(fh)

    total_players = len(career_data)
    print(f"Players in JSON: {total_players:,}")

    # Count total season rows to load
    rows_to_upsert = 0
    players_with_data = 0
    players_empty = 0
    for player in career_data.values():
        entries = player.get("career", []) if isinstance(player, dict) else []
        if entries:
            players_with_data += 1
            rows_to_upsert += len(entries)
        else:
            players_empty += 1

    print(f"Players with career data: {players_with_data:,}")
    print(f"Players with no data yet: {players_empty:,}")
    print(f"Total season rows to upsert: {rows_to_upsert:,}")

    if args.dry_run:
        print("\n(dry-run: no DB writes)")
        return 0

    try:
        import psycopg2
    except ModuleNotFoundError:
        print("psycopg2 required: pip install psycopg2-binary", file=sys.stderr)
        return 1

    try:
        conn = psycopg2.connect(
            host=args.host, port=args.port, dbname=args.dbname,
            user=args.user, password=args.password,
        )
    except Exception as exc:
        print(f"DB connection failed: {exc}", file=sys.stderr)
        return 1

    try:
        ensure_table(conn)

        now = datetime.utcnow()
        upserted = 0
        skipped_empty = 0
        BATCH = 500

        print(f"\nUpserting {rows_to_upsert:,} rows in batches of {BATCH}...")

        for player_id, player_data in career_data.items():
            if not isinstance(player_data, dict):
                skipped_empty += 1
                continue
            player_name = (player_data.get("player_name") or "").strip()
            entries = player_data.get("career", [])
            if not entries:
                skipped_empty += 1
                continue

            for entry in entries:
                label = str(entry.get("Season", "")).strip()
                if not label:
                    continue
                upsert_row(conn, player_id, player_name, entry, now)
                upserted += 1

            # Commit in batches to avoid holding a huge transaction
            if upserted % BATCH == 0:
                conn.commit()
                print(f"  {upserted:,} / {rows_to_upsert:,} upserted...", end="\r")

        conn.commit()

        print(f"\n{'=' * 70}")
        print("BULK LOAD COMPLETE")
        print("=" * 70)
        print(f"Total players in JSON:    {total_players:,}")
        print(f"Players with career data: {players_with_data:,}")
        print(f"Season rows upserted:     {upserted:,}")
        return 0

    except Exception as exc:
        conn.rollback()
        print(f"\nBulk load failed: {exc}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
