"""Load AYHL roster CSV files into the ayhl_roster table.

This script scans all ``*-ayhl-rosters.csv`` files under the rosters data dir
and upserts every raw player row into the ayhl_roster table.

Usage:
    python scripts/ayhl/load_rosters_to_db.py

Optional examples:
    python scripts/ayhl/load_rosters_to_db.py --dry-run
    python scripts/ayhl/load_rosters_to_db.py --data-dir scripts/ayhl/data/rosters
    python scripts/ayhl/load_rosters_to_db.py --host localhost --port 5432
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from uuid import uuid4


DEFAULT_DATA_DIR = Path(__file__).resolve().parent / "data" / "rosters"


@dataclass
class Counters:
    processed: int = 0
    upserted: int = 0
    rejected: int = 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load AYHL roster CSV files into PostgreSQL (ayhl_roster)"
    )
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="myhockeystats")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")
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
    return f"{start_year}-{start_year + 1} Season"


def ensure_table(conn) -> None:
    """Create ayhl_roster table if it does not exist."""
    with conn.cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS ayhl_roster (
                id              UUID            PRIMARY KEY,
                season_year     INTEGER         NOT NULL,
                season_id       VARCHAR(32),
                league_id       VARCHAR(32),
                team_id         VARCHAR(32),
                team_name       VARCHAR(255),
                player_id       VARCHAR(128),
                player_name_raw VARCHAR(255)    NOT NULL,
                jersey_number   VARCHAR(16),
                position        VARCHAR(16),
                height          VARCHAR(16),
                weight          VARCHAR(16),
                shoots          VARCHAR(8),
                birth_month     INTEGER,
                birth_year      INTEGER,
                hometown        VARCHAR(255),
                season_label    VARCHAR(32)     NOT NULL,
                scraped_at      TIMESTAMP       NOT NULL,
                UNIQUE (season_year, league_id, team_id, player_id)
            );
            CREATE INDEX IF NOT EXISTS idx_ayhl_roster_season_player
                ON ayhl_roster (season_label, player_id);
        """)
    conn.commit()


def upsert_row(conn, row: dict[str, str], scraped_at: datetime) -> None:
    birth_month, birth_year = parse_birthdate(row.get("birthdate", ""))
    season_year_str = row.get("season_year", "").strip()
    label = season_label_from_year(season_year_str)
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO ayhl_roster (
                id, season_year, season_id, league_id, team_id, team_name,
                player_id, player_name_raw, jersey_number, position,
                height, weight, shoots, birth_month, birth_year, hometown,
                season_label, scraped_at
            ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (season_year, league_id, team_id, player_id) DO UPDATE SET
                season_id       = EXCLUDED.season_id,
                team_name       = EXCLUDED.team_name,
                player_name_raw = EXCLUDED.player_name_raw,
                jersey_number   = EXCLUDED.jersey_number,
                position        = EXCLUDED.position,
                height          = EXCLUDED.height,
                weight          = EXCLUDED.weight,
                shoots          = EXCLUDED.shoots,
                birth_month     = EXCLUDED.birth_month,
                birth_year      = EXCLUDED.birth_year,
                hometown        = EXCLUDED.hometown,
                season_label    = EXCLUDED.season_label,
                scraped_at      = EXCLUDED.scraped_at
            """,
            (
                str(uuid4()),
                int(season_year_str) if season_year_str.isdigit() else None,
                row.get("seasonid", "").strip() or None,
                row.get("leagueid", "").strip() or None,
                row.get("teamid", "").strip() or None,
                row.get("team", "").strip() or None,
                row.get("playerid", "").strip() or None,
                row.get("player", "").strip(),
                row.get("number", "").strip() or None,
                row.get("pos", "").strip() or None,
                row.get("ht", "").strip() or None,
                row.get("wt", "").strip() or None,
                row.get("shot", "").strip() or None,
                birth_month or None,
                birth_year or None,
                row.get("hometown", "").strip() or None,
                label,
                scraped_at,
            ),
        )





def load_rosters(conn, data_dir: Path, dry_run: bool) -> Counters:
    counters = Counters()
    files = sorted(data_dir.glob("*-ayhl-rosters.csv"))
    if not files:
        raise FileNotFoundError(f"No *-ayhl-rosters.csv files found in {data_dir}")

    scraped_at = datetime.utcnow()

    for csv_file in files:
        print(f"  Processing {csv_file.name}")
        with csv_file.open("r", encoding="utf-8", newline="") as fh:
            reader = csv.DictReader(fh)
            for row in reader:
                counters.processed += 1
                if not row.get("player", "").strip():
                    counters.rejected += 1
                    continue
                if dry_run:
                    counters.upserted += 1
                    continue
                upsert_row(conn, row, scraped_at)
                counters.upserted += 1

        if not dry_run:
            conn.commit()

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
            print(f"Processed: {counters.processed}")
            print(f"Upserted:  {counters.upserted}")
            print(f"Rejected:  {counters.rejected}")
            return 0
        except Exception as exc:
            print(f"Loader failed: {exc}", file=sys.stderr)
            return 1

    try:
        import psycopg2
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
        ensure_table(conn)
        counters = load_rosters(conn, data_dir, False)
        print("=" * 60)
        print("LOAD COMPLETE")
        print("=" * 60)
        print(f"Processed: {counters.processed}")
        print(f"Upserted:  {counters.upserted}")
        print(f"Rejected:  {counters.rejected}")
        return 0
    except Exception as exc:
        conn.rollback()
        print(f"Load failed: {exc}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())