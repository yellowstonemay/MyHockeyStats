from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TARGET_TABLES = ("thf_player_career", "ahf_player_career")
SPLIT_SEASON_RE = re.compile(r"^(?P<year>\d{4}-\d{4})\s+(?P<phase>Regular|Playoff)\s+Season$", re.IGNORECASE)
BASE_SEASON_RE = re.compile(r"^(?P<year>\d{4}-\d{4})\s+Season$", re.IGNORECASE)
INT_COLUMNS = ("games_played", "goals", "assists", "points", "penalties")
NUMERIC_COLUMNS = ("pim",)


def utc_now_naive() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Merge split THF/AHF career season rows such as '2022-2023 Regular Season' "
            "and '2022-2023 Playoff Season' into a single '2022-2023 Season' row."
        )
    )
    parser.add_argument("--dry-run", action="store_true", help="Report changes without writing to the database")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="myhockeystats")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")
    return parser.parse_args()


def connect_db(args: argparse.Namespace):
    try:
        import psycopg2
        import psycopg2.extras
    except ModuleNotFoundError:
        print("psycopg2 required: pip install psycopg2-binary", file=sys.stderr)
        raise SystemExit(1)

    try:
        return psycopg2.connect(
            host=args.host,
            port=args.port,
            dbname=args.dbname,
            user=args.user,
            password=args.password,
            cursor_factory=psycopg2.extras.RealDictCursor,
        )
    except Exception as exc:
        print(f"DB connection failed: {exc}", file=sys.stderr)
        raise SystemExit(1)


def get_table_columns(conn, table_name: str) -> set[str]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = %s
            """,
            (table_name,),
        )
        return {row["column_name"] for row in cur.fetchall()}


def parse_season_label(season_label: str | None) -> tuple[str | None, str | None]:
    text = (season_label or "").strip()
    match = SPLIT_SEASON_RE.match(text)
    if match:
        return match.group("year"), match.group("phase").lower()

    match = BASE_SEASON_RE.match(text)
    if match:
        return match.group("year"), "base"

    return None, None


def choose_preferred_value(rows: list[dict], field: str):
    def priority(row: dict) -> int:
        _, phase = parse_season_label(row.get("season_label"))
        if phase == "regular":
            return 0
        if phase == "base":
            return 1
        if phase == "playoff":
            return 2
        return 3

    for row in sorted(rows, key=priority):
        value = row.get(field)
        if value not in (None, ""):
            return value
    return None


def sum_nullable(rows: list[dict], field: str):
    values = [row.get(field) for row in rows if row.get(field) is not None]
    if not values:
        return None
    total = sum(values)
    return total


def group_merge_candidates(rows: list[dict], has_team_id: bool) -> list[dict]:
    grouped: dict[tuple[str, str, str], list[dict]] = {}

    for row in rows:
        season_year, phase = parse_season_label(row.get("season_label"))
        if not season_year:
            continue

        team_key = row.get("team_id") if has_team_id else row.get("team_name")
        grouped.setdefault((row["source_player_id"], team_key or "", season_year), []).append(row)

    merge_groups = []
    for (source_player_id, _, season_year), group_rows in grouped.items():
        if not any(parse_season_label(r.get("season_label"))[1] in {"regular", "playoff"} for r in group_rows):
            continue

        merged = {
            "id": str(uuid4()),
            "source_player_id": source_player_id,
            "player_name": choose_preferred_value(group_rows, "player_name"),
            "season_label": f"{season_year} Season",
            "league_name": choose_preferred_value(group_rows, "league_name"),
            "team_name": choose_preferred_value(group_rows, "team_name"),
            "jersey_number": choose_preferred_value(group_rows, "jersey_number"),
            "last_scraped_at": max((r.get("last_scraped_at") for r in group_rows if r.get("last_scraped_at") is not None), default=None),
            "created_at": min((r.get("created_at") for r in group_rows if r.get("created_at") is not None), default=utc_now_naive()),
            "updated_at": utc_now_naive(),
        }

        if has_team_id:
            merged["team_id"] = choose_preferred_value(group_rows, "team_id") or "UNKNOWN"

        for column in INT_COLUMNS + NUMERIC_COLUMNS:
            merged[column] = sum_nullable(group_rows, column)

        merge_groups.append(
            {
                "season_year": season_year,
                "source_player_id": source_player_id,
                "rows": group_rows,
                "merged": merged,
            }
        )

    return merge_groups


def fetch_candidate_rows(conn, table_name: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT *
            FROM {table_name}
            WHERE season_label ~ '^[0-9]{{4}}-[0-9]{{4}} (Regular|Playoff) Season$'
               OR season_label ~ '^[0-9]{{4}}-[0-9]{{4}} Season$'
            ORDER BY source_player_id, season_label
            """
        )
        return list(cur.fetchall())


def apply_merge_group(conn, table_name: str, group: dict, has_team_id: bool) -> None:
    row_ids = [row["id"] for row in group["rows"]]
    merged = group["merged"]

    with conn.cursor() as cur:
        cur.execute(f"DELETE FROM {table_name} WHERE id = ANY(%s::uuid[])", (row_ids,))

        columns = [
            "id",
            "source_player_id",
            "player_name",
            "season_label",
            "league_name",
            "team_name",
            "jersey_number",
            "games_played",
            "goals",
            "assists",
            "points",
            "penalties",
            "pim",
            "last_scraped_at",
            "created_at",
            "updated_at",
        ]
        if has_team_id:
            columns.append("team_id")

        placeholders = ", ".join(["%s"] * len(columns))
        values = [merged.get(column) for column in columns]
        cur.execute(
            f"INSERT INTO {table_name} ({', '.join(columns)}) VALUES ({placeholders})",
            values,
        )


def main() -> int:
    args = parse_args()
    conn = connect_db(args)

    try:
        total_groups = 0
        total_source_rows = 0
        for table_name in TARGET_TABLES:
            columns = get_table_columns(conn, table_name)
            has_team_id = "team_id" in columns
            rows = fetch_candidate_rows(conn, table_name)
            merge_groups = group_merge_candidates(rows, has_team_id)

            print(f"\nTable: {table_name}")
            print(f"  Candidate rows: {len(rows):,}")
            print(f"  Merge groups:   {len(merge_groups):,}")
            print(f"  Rows replaced:  {sum(len(group['rows']) for group in merge_groups):,}")

            total_groups += len(merge_groups)
            total_source_rows += sum(len(group["rows"]) for group in merge_groups)

            if args.dry_run:
                continue

            for group in merge_groups:
                apply_merge_group(conn, table_name, group, has_team_id)

        if args.dry_run:
            print("\n(dry-run: no DB writes)")
        else:
            conn.commit()
            print("\nMerge complete.")

        print(f"Total merge groups: {total_groups:,}")
        print(f"Total source rows replaced: {total_source_rows:,}")
        return 0
    except Exception as exc:
        conn.rollback()
        print(f"Merge failed: {exc}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())