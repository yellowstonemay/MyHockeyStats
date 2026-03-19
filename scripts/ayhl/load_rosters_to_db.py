"""Load AYHL roster CSV files into the integration import tables.

This script scans all ``*-ayhl-rosters.csv`` files under ``scripts/ayhl/data``
and loads player identity rows into the integration tables used by the backend.

It is intended for development and testing with real roster data.

Usage:
    python scripts/ayhl/load_rosters_to_db.py

Optional examples:
    python scripts/ayhl/load_rosters_to_db.py --dry-run
    python scripts/ayhl/load_rosters_to_db.py --data-dir scripts/ayhl/data
    python scripts/ayhl/load_rosters_to_db.py --host localhost --port 5432
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from uuid import uuid4


ROOT_DIR = Path(__file__).resolve().parents[2]
# Default to the rosters subdirectory after reorganizing CSVs
DEFAULT_DATA_DIR = Path(__file__).resolve().parent / "data" / "rosters"
MIGRATION_FILE = (
    ROOT_DIR
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V20260312_01__integration_core_tables.sql"
)


@dataclass
class Counters:
    processed: int = 0
    accepted: int = 0
    rejected: int = 0
    duplicate_skipped: int = 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load AYHL roster CSV files into PostgreSQL")
    parser.add_argument("--host", default="localhost", help="PostgreSQL host")
    parser.add_argument("--port", type=int, default=5432, help="PostgreSQL port")
    parser.add_argument("--dbname", default="myhockeystats", help="PostgreSQL database name")
    parser.add_argument("--user", default="postgres", help="PostgreSQL user")
    parser.add_argument("--password", default="postgres", help="PostgreSQL password")
    parser.add_argument(
        "--data-dir",
        default=str(DEFAULT_DATA_DIR),
        help="Directory containing *-ayhl-rosters.csv files",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse files and report counts without writing to the database",
    )
    return parser.parse_args()


def normalize_player_name(raw_name: str) -> str:
    cleaned = " ".join(raw_name.replace('"', '').strip().split())
    if not cleaned:
        return ""

    if "," in cleaned:
        last_name, first_name = [part.strip() for part in cleaned.split(",", 1)]
        cleaned = f"{first_name} {last_name}".strip()

    lowered = cleaned.lower()
    allowed = []
    for char in lowered:
        if char.isalnum() or char.isspace():
            allowed.append(char)
        else:
            allowed.append(" ")
    return " ".join("".join(allowed).split())


def parse_birthdate(raw_birthdate: str) -> tuple[int, int]:
    value = raw_birthdate.strip()
    if not value:
        return 0, 0

    parts = value.split("/")
    if len(parts) != 2:
        return 0, 0

    try:
        month = int(parts[0])
        year = int(parts[1])
    except ValueError:
        return 0, 0

    if month < 1 or month > 12 or year < 1900:
        return 0, 0
    return month, year


def season_label_from_year(raw_year: str) -> str:
    try:
        start_year = int(raw_year)
    except ValueError:
        return raw_year.strip()
    return f"{start_year}-{start_year + 1}"


def source_hash_for_row(row: dict[str, str]) -> str:
    payload = "|".join(
        [
            row.get("season_year", "").strip(),
            row.get("leagueid", "").strip(),
            row.get("teamid", "").strip(),
            row.get("team", "").strip(),
            row.get("player", "").strip(),
            row.get("birthdate", "").strip(),
            row.get("hometown", "").strip(),
        ]
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def iter_roster_files(data_dir: Path) -> Iterable[Path]:
    return sorted(data_dir.glob("*-ayhl-rosters.csv"))


def ensure_integration_schema(connection) -> None:
    if not MIGRATION_FILE.exists():
        raise FileNotFoundError(f"Migration file not found: {MIGRATION_FILE}")

    with connection.cursor() as cursor:
        cursor.execute(MIGRATION_FILE.read_text(encoding="utf-8"))
        cursor.execute(
            "ALTER TABLE integration_imported_player_record ADD COLUMN IF NOT EXISTS source_team_name VARCHAR(255)"
        )
        cursor.execute(
            "ALTER TABLE integration_imported_player_record ADD COLUMN IF NOT EXISTS source_club_name VARCHAR(255)"
        )
    connection.commit()


def create_import_run(connection) -> str:
    run_id = str(uuid4())
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO integration_import_run (
                id, source, trigger_type, status, started_at,
                processed_count, accepted_count, rejected_count, duplicate_skipped_count
            )
            VALUES (%s, %s, %s, %s, NOW(), 0, 0, 0, 0)
            """,
            (run_id, "AYHL", "OPERATOR_MANUAL", "RUNNING"),
        )
    connection.commit()
    return run_id


def finalize_import_run(connection, run_id: str, counters: Counters) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE integration_import_run
            SET status = %s,
                ended_at = NOW(),
                processed_count = %s,
                accepted_count = %s,
                rejected_count = %s,
                duplicate_skipped_count = %s
            WHERE id = %s
            """,
            (
                "COMPLETED",
                counters.processed,
                counters.accepted,
                counters.rejected,
                counters.duplicate_skipped,
                run_id,
            ),
        )
    connection.commit()


def insert_player_record(connection, run_id: str, row: dict[str, str]) -> bool:
    player_name_raw = row.get("player", "").strip()
    player_name_normalized = normalize_player_name(player_name_raw)
    birth_month, birth_year = parse_birthdate(row.get("birthdate", ""))
    season_label = season_label_from_year(row.get("season_year", ""))
    source_hash = source_hash_for_row(row)

    if not player_name_raw or not player_name_normalized or birth_month == 0 or birth_year == 0:
        return False

    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO integration_imported_player_record (
                id,
                source,
                source_player_id,
                source_team_id,
                source_team_name,
                source_club_id,
                source_club_name,
                player_name_raw,
                player_name_normalized,
                birth_month,
                birth_year,
                season_label,
                import_run_id,
                source_hash,
                created_at
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
            ON CONFLICT (source, source_hash) DO UPDATE
            SET source_team_id = EXCLUDED.source_team_id,
                source_team_name = EXCLUDED.source_team_name,
                source_club_id = EXCLUDED.source_club_id,
                source_club_name = EXCLUDED.source_club_name,
                season_label = EXCLUDED.season_label,
                import_run_id = EXCLUDED.import_run_id
            """,
            (
                str(uuid4()),
                "AYHL",
                None,
                row.get("teamid", "").strip() or None,
                row.get("team", "").strip() or None,
                row.get("leagueid", "").strip() or None,
                "AYHL",
                player_name_raw,
                player_name_normalized,
                birth_month,
                birth_year,
                season_label,
                run_id,
                source_hash,
            ),
        )
        return cursor.rowcount == 1


def load_rosters(connection, data_dir: Path, dry_run: bool) -> Counters:
    counters = Counters()
    files = list(iter_roster_files(data_dir))
    if not files:
        raise FileNotFoundError(f"No *-ayhl-rosters.csv files found in {data_dir}")

    run_id = None if dry_run else create_import_run(connection)

    for csv_file in files:
        print(f"Processing {csv_file.name}")
        with csv_file.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                counters.processed += 1

                player_name_raw = row.get("player", "").strip()
                birth_month, birth_year = parse_birthdate(row.get("birthdate", ""))
                if not player_name_raw or birth_month == 0 or birth_year == 0:
                    counters.rejected += 1
                    continue

                if dry_run:
                    counters.accepted += 1
                    continue

                inserted = insert_player_record(connection, run_id, row)
                if inserted:
                    counters.accepted += 1
                else:
                    counters.duplicate_skipped += 1

        if not dry_run:
            connection.commit()

    if not dry_run and run_id is not None:
        finalize_import_run(connection, run_id, counters)

    return counters


def main() -> int:
    args = parse_args()
    data_dir = Path(args.data_dir).resolve()

    if args.dry_run:
        try:
            counters = load_rosters(None, data_dir, True)
            print("=" * 60)
            print("DRY RUN")
            print("=" * 60)
            print(f"Processed:         {counters.processed}")
            print(f"Inserted/Accepted: {counters.accepted}")
            print(f"Rejected:          {counters.rejected}")
            print(f"Duplicate skipped: {counters.duplicate_skipped}")
            print(f"Data directory:    {data_dir}")
            return 0
        except Exception as exc:
            print(f"Loader failed: {exc}", file=sys.stderr)
            return 1

    try:
        import psycopg2
    except ModuleNotFoundError:
        print(
            "psycopg2 is required for database loading. Install it with: pip install psycopg2-binary",
            file=sys.stderr,
        )
        return 1

    try:
        connection = psycopg2.connect(
            host=args.host,
            port=args.port,
            dbname=args.dbname,
            user=args.user,
            password=args.password,
        )
    except Exception as exc:  # pragma: no cover - connection failures are runtime-dependent
        print(f"Failed to connect to PostgreSQL: {exc}", file=sys.stderr)
        return 1

    try:
        ensure_integration_schema(connection)

        counters = load_rosters(connection, data_dir, False)
        print("=" * 60)
        print("LOAD COMPLETE")
        print("=" * 60)
        print(f"Processed:         {counters.processed}")
        print(f"Inserted/Accepted: {counters.accepted}")
        print(f"Rejected:          {counters.rejected}")
        print(f"Duplicate skipped: {counters.duplicate_skipped}")
        print(f"Data directory:    {data_dir}")
        return 0
    except Exception as exc:
        connection.rollback()
        print(f"Loader failed: {exc}", file=sys.stderr)
        return 1
    finally:
        connection.close()


if __name__ == "__main__":
    raise SystemExit(main())