from __future__ import annotations

import argparse
import csv
import os
import re
import sys
from datetime import datetime
from decimal import Decimal, InvalidOperation
from uuid import uuid4

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PLAYERS_CSV = os.path.join(SCRIPT_DIR, "6579_players.csv")
DEFAULT_STATS_CSV = os.path.join(SCRIPT_DIR, "6579_players_stats.csv")

LEAGUE_TABLES = {
    "THF": "thf_player_career",
    "AHF": "ahf_player_career",
}

SEASON_PREFIX_RE = re.compile(r"^(THF|AHF)\s*-\s*(.+)$", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Create and populate thf_player_career and ahf_player_career "
            "from gamesheet CSV exports"
        )
    )
    parser.add_argument("--players-csv", default=DEFAULT_PLAYERS_CSV)
    parser.add_argument("--stats-csv", default=DEFAULT_STATS_CSV)
    parser.add_argument("--dry-run", action="store_true",
                        help="Parse and report counts without writing to DB")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="myhockeystats")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")
    return parser.parse_args()


def normalize_text(value: str | None) -> str:
    return (value or "").strip()


def coerce_int(value: str | None) -> int | None:
    text = normalize_text(value)
    if not text:
        return None
    try:
        number = Decimal(text)
    except InvalidOperation:
        return None
    return int(number)


def coerce_numeric_1(value: str | None) -> Decimal | None:
    text = normalize_text(value)
    if not text:
        return None
    try:
        number = Decimal(text)
    except InvalidOperation:
        return None
    return number.quantize(Decimal("0.1"))


def is_invalid_player(flags: str | None) -> bool:
    return normalize_text(flags).lower().replace(" ", "") == "[x]"


def build_valid_player_index(players_csv: str) -> tuple[dict[str, dict[str, str]], int, int]:
    valid_players: dict[str, dict[str, str]] = {}
    total_players = 0
    invalid_players = 0

    with open(players_csv, "r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            total_players += 1
            if is_invalid_player(row.get("flags")):
                invalid_players += 1
                continue

            player_id = normalize_text(row.get("id"))
            if not player_id:
                continue

            player_name = " ".join(
                part for part in [normalize_text(row.get("firstName")), normalize_text(row.get("lastName"))] if part
            )
            valid_players[player_id] = {
                "player_name": player_name,
                "jersey_number": normalize_text(row.get("jersey")) or None,
            }

    return valid_players, total_players, invalid_players


def normalize_season_label(season: str) -> tuple[str | None, str | None]:
    match = SEASON_PREFIX_RE.match(normalize_text(season))
    if not match:
        return None, None

    league = match.group(1).upper()
    season_label = normalize_text(match.group(2))
    if not season_label:
        return None, None
    return league, season_label


def ensure_table(conn, table_name: str, index_name: str) -> None:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            CREATE TABLE IF NOT EXISTS {table_name} (
                id               UUID         PRIMARY KEY,
                source_player_id VARCHAR(128) NOT NULL,
                player_name      VARCHAR(255),
                season_label     VARCHAR(255) NOT NULL,
                league_name      VARCHAR(255),
                team_name        VARCHAR(1024),
                jersey_number    VARCHAR(16),
                games_played     INTEGER,
                goals            INTEGER,
                assists          INTEGER,
                points           INTEGER,
                penalties        INTEGER,
                pim              NUMERIC(10,1),
                last_scraped_at  TIMESTAMP,
                created_at       TIMESTAMP    NOT NULL,
                updated_at       TIMESTAMP    NOT NULL,
                UNIQUE (source_player_id, season_label)
            );

            CREATE INDEX IF NOT EXISTS {index_name}
                ON {table_name} (source_player_id, season_label);
            """
        )
        cur.execute(
            f"""
            ALTER TABLE {table_name}
                ALTER COLUMN season_label TYPE VARCHAR(255),
                ALTER COLUMN team_name TYPE VARCHAR(1024),
                ALTER COLUMN pim TYPE NUMERIC(10,1) USING pim::NUMERIC(10,1);
            """
        )
    conn.commit()


def upsert_row(conn, table_name: str, row: dict[str, str | int | Decimal | None], now: datetime) -> None:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            INSERT INTO {table_name} (
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
                row["source_player_id"],
                row["player_name"],
                row["season_label"],
                row["league_name"],
                row["team_name"],
                row["jersey_number"],
                row["games_played"],
                row["goals"],
                row["assists"],
                row["points"],
                row["penalties"],
                row["pim"],
                now,
                now,
                now,
            ),
        )


def collect_rows(stats_csv: str, valid_players: dict[str, dict[str, str | None]]) -> tuple[dict[str, list[dict[str, str | int | Decimal | None]]], dict[str, int], int]:
    rows_by_table: dict[str, list[dict[str, str | int | Decimal | None]]] = {
        "thf_player_career": [],
        "ahf_player_career": [],
    }
    counts = {"thf_player_career": 0, "ahf_player_career": 0}
    skipped_non_matching = 0

    with open(stats_csv, "r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        for stats_row in reader:
            player_id = normalize_text(stats_row.get("player_id"))
            if not player_id or player_id not in valid_players:
                skipped_non_matching += 1
                continue

            league_code, season_label = normalize_season_label(stats_row.get("season", ""))
            if not league_code or not season_label:
                skipped_non_matching += 1
                continue

            table_name = LEAGUE_TABLES[league_code]
            player_meta = valid_players[player_id]
            player_name = normalize_text(stats_row.get("player_name")) or player_meta["player_name"]
            row = {
                "source_player_id": player_id,
                "player_name": player_name or None,
                "season_label": season_label,
                "league_name": league_code,
                "team_name": normalize_text(stats_row.get("team")) or None,
                "jersey_number": player_meta["jersey_number"],
                "games_played": coerce_int(stats_row.get("gp")),
                "goals": coerce_int(stats_row.get("g")),
                "assists": coerce_int(stats_row.get("a")),
                "points": coerce_int(stats_row.get("pts")),
                "penalties": None,
                "pim": coerce_numeric_1(stats_row.get("pim")),
            }
            rows_by_table[table_name].append(row)
            counts[table_name] += 1

    return rows_by_table, counts, skipped_non_matching


def main() -> int:
    args = parse_args()

    print("=" * 70)
    print("GAMESHEET CAREER BULK LOAD")
    print("=" * 70)
    print(f"Players CSV: {args.players_csv}")
    print(f"Stats CSV:   {args.stats_csv}")

    for file_path in (args.players_csv, args.stats_csv):
        if not os.path.exists(file_path):
            print(f"ERROR: {file_path} not found", file=sys.stderr)
            return 1

    valid_players, total_players, invalid_players = build_valid_player_index(args.players_csv)
    rows_by_table, counts, skipped_non_matching = collect_rows(args.stats_csv, valid_players)

    print(f"Players in source CSV:      {total_players:,}")
    print(f"Invalid players skipped:    {invalid_players:,}")
    print(f"Valid players indexed:      {len(valid_players):,}")
    print(f"THF season rows to upsert:  {counts['thf_player_career']:,}")
    print(f"AHF season rows to upsert:  {counts['ahf_player_career']:,}")
    print(f"Stats rows skipped:         {skipped_non_matching:,}")

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
            host=args.host,
            port=args.port,
            dbname=args.dbname,
            user=args.user,
            password=args.password,
        )
    except Exception as exc:
        print(f"DB connection failed: {exc}", file=sys.stderr)
        return 1

    try:
        ensure_table(conn, "thf_player_career", "idx_thf_career_player_season")
        ensure_table(conn, "ahf_player_career", "idx_ahf_career_player_season")

        now = datetime.utcnow()
        upserted = {"thf_player_career": 0, "ahf_player_career": 0}
        batch_size = 500
        processed = 0
        total_rows = counts["thf_player_career"] + counts["ahf_player_career"]

        for table_name in ("thf_player_career", "ahf_player_career"):
            for row in rows_by_table[table_name]:
                upsert_row(conn, table_name, row, now)
                upserted[table_name] += 1
                processed += 1
                if processed % batch_size == 0:
                    conn.commit()
                    print(f"  {processed:,} / {total_rows:,} upserted...", end="\r")

        conn.commit()

        print(f"\n{'=' * 70}")
        print("BULK LOAD COMPLETE")
        print("=" * 70)
        print(f"THF rows upserted: {upserted['thf_player_career']:,}")
        print(f"AHF rows upserted: {upserted['ahf_player_career']:,}")
        return 0
    except Exception as exc:
        conn.rollback()
        print(f"Bulk load failed: {exc}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())