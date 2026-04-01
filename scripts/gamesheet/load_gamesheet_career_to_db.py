from __future__ import annotations

import argparse
import ast
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
SPLIT_SEASON_RE = re.compile(r"^(?P<year>\d{4}-\d{4})\s+(?P<phase>Regular|Playoff)\s+Season$", re.IGNORECASE)


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
    parser.add_argument(
        "--cleanup-invalid-only",
        action="store_true",
        help="Delete already-imported rows for players marked invalid in players CSV, without reloading stats",
    )
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
    text = normalize_text(flags)
    if not text:
        return False

    compact = text.lower().replace(" ", "")
    if compact == "[x]":
        return True

    try:
        parsed = ast.literal_eval(text)
    except (SyntaxError, ValueError):
        parsed = None

    if isinstance(parsed, (list, tuple, set)):
        return any(normalize_text(str(item)).lower() == "x" for item in parsed)

    return False


def build_valid_player_index(players_csv: str) -> tuple[dict[str, dict[str, str | None]], set[str], int, int]:
    valid_players: dict[str, dict[str, str]] = {}
    invalid_player_ids: set[str] = set()
    total_players = 0
    invalid_players = 0

    with open(players_csv, "r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            total_players += 1
            player_id = normalize_text(row.get("id"))

            if is_invalid_player(row.get("flags")):
                invalid_players += 1
                if player_id:
                    invalid_player_ids.add(player_id)
                continue

            if not player_id:
                continue

            player_name = " ".join(
                part for part in [normalize_text(row.get("firstName")), normalize_text(row.get("lastName"))] if part
            )
            valid_players[player_id] = {
                "player_name": player_name,
                "jersey_number": normalize_text(row.get("jersey")) or None,
            }

    return valid_players, invalid_player_ids, total_players, invalid_players


def season_phase_priority(phase: str) -> int:
    if phase == "regular":
        return 0
    if phase == "base":
        return 1
    if phase == "playoff":
        return 2
    return 3


def normalize_season_label(season: str) -> tuple[str | None, str | None, str | None]:
    match = SEASON_PREFIX_RE.match(normalize_text(season))
    if not match:
        return None, None, None

    league = match.group(1).upper()
    season_label = normalize_text(match.group(2))
    if not season_label:
        return None, None, None

    split_match = SPLIT_SEASON_RE.match(season_label)
    if split_match:
        return league, f"{split_match.group('year')} Season", split_match.group("phase").lower()

    if re.match(r"^\d{4}-\d{4}\s+Season$", season_label, re.IGNORECASE):
        return league, season_label, "base"

    return league, season_label, "other"


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
        cur.execute(
            f"""
            ALTER TABLE {table_name}
                ADD COLUMN IF NOT EXISTS team_id VARCHAR(128);

            UPDATE {table_name}
            SET team_id = 'UNKNOWN'
            WHERE team_id IS NULL;

            ALTER TABLE {table_name}
                ALTER COLUMN team_id SET NOT NULL;
            """
        )
        cur.execute(
            f"""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_{table_name}_player_season_team
                ON {table_name} (source_player_id, season_label, team_id);
            """
        )
    conn.commit()


def upsert_row(conn, table_name: str, row: dict[str, str | int | Decimal | None], now: datetime) -> None:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            INSERT INTO {table_name} (
                id, source_player_id, player_name, season_label,
                league_name, team_name, team_id, jersey_number,
                games_played, goals, assists, points, penalties, pim,
                last_scraped_at, created_at, updated_at
            ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (source_player_id, season_label, team_id) DO UPDATE SET
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
                row["team_id"],
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


def merge_preferred_text(aggregate: dict[str, str | int | Decimal | None], field: str, value: str | None, phase: str) -> None:
    text = normalize_text(value)
    if not text:
        return

    rank_key = f"__{field}_rank"
    current_rank = aggregate.get(rank_key)
    candidate_rank = season_phase_priority(phase)
    if current_rank is None or candidate_rank < current_rank or not aggregate.get(field):
        aggregate[field] = text
        aggregate[rank_key] = candidate_rank


def add_nullable_number(aggregate: dict[str, str | int | Decimal | None], field: str, value: int | Decimal | None) -> None:
    if value is None:
        return
    current = aggregate.get(field)
    aggregate[field] = value if current is None else current + value


def finalize_aggregate(aggregate: dict[str, str | int | Decimal | None]) -> dict[str, str | int | Decimal | None]:
    return {key: value for key, value in aggregate.items() if not key.startswith("__")}


def collect_rows(stats_csv: str, valid_players: dict[str, dict[str, str | None]]) -> tuple[dict[str, list[dict[str, str | int | Decimal | None]]], dict[str, int], int]:
    aggregated_by_table: dict[str, dict[tuple[str, str], dict[str, str | int | Decimal | None]]] = {
        "thf_player_career": {},
        "ahf_player_career": {},
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

            league_code, season_label, phase = normalize_season_label(stats_row.get("season", ""))
            if not league_code or not season_label:
                skipped_non_matching += 1
                continue

            table_name = LEAGUE_TABLES[league_code]
            player_meta = valid_players[player_id]
            aggregate_key = (player_id, season_label)
            aggregate = aggregated_by_table[table_name].setdefault(
                aggregate_key,
                {
                    "source_player_id": player_id,
                    "player_name": None,
                    "season_label": season_label,
                    "league_name": league_code,
                    "team_name": None,
                    "team_id": "UNKNOWN",
                    "jersey_number": None,
                    "games_played": None,
                    "goals": None,
                    "assists": None,
                    "points": None,
                    "penalties": None,
                    "pim": None,
                },
            )

            merge_preferred_text(aggregate, "player_name", normalize_text(stats_row.get("player_name")) or player_meta["player_name"], phase or "other")
            merge_preferred_text(aggregate, "league_name", league_code, phase or "other")
            merge_preferred_text(aggregate, "team_name", normalize_text(stats_row.get("team")), phase or "other")
            merge_preferred_text(aggregate, "jersey_number", player_meta["jersey_number"], phase or "other")

            add_nullable_number(aggregate, "games_played", coerce_int(stats_row.get("gp")))
            add_nullable_number(aggregate, "goals", coerce_int(stats_row.get("g")))
            add_nullable_number(aggregate, "assists", coerce_int(stats_row.get("a")))
            add_nullable_number(aggregate, "points", coerce_int(stats_row.get("pts")))
            add_nullable_number(aggregate, "pim", coerce_numeric_1(stats_row.get("pim")))

    rows_by_table = {
        table_name: [finalize_aggregate(row) for row in aggregated.values()]
        for table_name, aggregated in aggregated_by_table.items()
    }
    counts = {table_name: len(rows) for table_name, rows in rows_by_table.items()}

    return rows_by_table, counts, skipped_non_matching


def delete_invalid_rows(conn, invalid_player_ids: set[str]) -> dict[str, int]:
    deleted = {"thf_player_career": 0, "ahf_player_career": 0}
    if not invalid_player_ids:
        return deleted

    with conn.cursor() as cur:
        for table_name in ("thf_player_career", "ahf_player_career"):
            cur.execute(
                f"DELETE FROM {table_name} WHERE source_player_id = ANY(%s)",
                (sorted(invalid_player_ids),),
            )
            deleted[table_name] = cur.rowcount

    conn.commit()
    return deleted


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

    valid_players, invalid_player_ids, total_players, invalid_players = build_valid_player_index(args.players_csv)
    rows_by_table, counts, skipped_non_matching = collect_rows(args.stats_csv, valid_players)

    print(f"Players in source CSV:      {total_players:,}")
    print(f"Invalid players skipped:    {invalid_players:,}")
    print(f"Invalid player IDs:         {len(invalid_player_ids):,}")
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

        deleted = delete_invalid_rows(conn, invalid_player_ids)

        if args.cleanup_invalid_only:
            print(f"\n{'=' * 70}")
            print("INVALID ROW CLEANUP COMPLETE")
            print("=" * 70)
            print(f"THF invalid rows deleted: {deleted['thf_player_career']:,}")
            print(f"AHF invalid rows deleted: {deleted['ahf_player_career']:,}")
            return 0

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
        print(f"THF invalid rows deleted: {deleted['thf_player_career']:,}")
        print(f"AHF invalid rows deleted: {deleted['ahf_player_career']:,}")
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