"""Weekly AYHL update orchestrator.

Runs every week during the active season (April – March) to refresh team
and roster data for the current season, then loads rosters to the database.

Steps:
  1. discover_teams.py   – find all leagues / teams for the season
  2. scrape_rosters.py   – scrape player rosters for every team
  3. load_rosters_to_db.py – upsert rosters into ayhl_roster

Season logic:
  The hockey season runs April through March of the following year.
  Season year = April start year.
  - Month  4-12 → season_year = current year
  - Month  1-3  → season_year = current year - 1

Usage:
    python scripts/ayhl/weekly_update.py
    python scripts/ayhl/weekly_update.py --season 2025
    python scripts/ayhl/weekly_update.py --dry-run       # skip DB write
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from datetime import date

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def current_season_year() -> int:
    today = date.today()
    return today.year if today.month >= 4 else today.year - 1


def run(cmd: list, **kwargs) -> subprocess.CompletedProcess:
    print(f"\n>>> {' '.join(str(c) for c in cmd)}")
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        print(f"  FAILED (exit {result.returncode})", file=sys.stderr)
        sys.exit(result.returncode)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Weekly AYHL team and roster update")
    parser.add_argument("--season", type=int, default=None,
                        help="Season start year (default: auto-detect from today's date)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Run discovery/scraping but skip database load")
    parser.add_argument("--host", default="localhost", help="PostgreSQL host")
    parser.add_argument("--port", type=int, default=5432, help="PostgreSQL port")
    parser.add_argument("--dbname", default="myhockeystats", help="Database name")
    parser.add_argument("--user", default="postgres", help="PostgreSQL user")
    parser.add_argument("--password", default="postgres", help="PostgreSQL password")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    season_year = args.season or current_season_year()
    python = sys.executable

    print("=" * 70)
    print(f"WEEKLY AYHL UPDATE — Season {season_year}-{season_year + 1}")
    print("=" * 70)

    # ------------------------------------------------------------------ #
    # Step 1: Discover leagues and teams                                   #
    # ------------------------------------------------------------------ #
    print("\nStep 1: Discover leagues and teams...")
    run([python, os.path.join(SCRIPT_DIR, "discover_teams.py"),
         "--season", str(season_year)])

    # ------------------------------------------------------------------ #
    # Step 2: Scrape rosters                                               #
    # ------------------------------------------------------------------ #
    print("\nStep 2: Scrape rosters for all teams...")
    run([python, os.path.join(SCRIPT_DIR, "scrape_rosters.py"),
         "--season", str(season_year)])

    # ------------------------------------------------------------------ #
    # Step 3: Load rosters into the database                               #
    # ------------------------------------------------------------------ #
    if args.dry_run:
        print("\nStep 3: [DRY-RUN] Skipping database load.")
    else:
        print("\nStep 3: Load rosters into the database...")
        run([
            python, os.path.join(SCRIPT_DIR, "load_rosters_to_db.py"),
            "--host", args.host,
            "--port", str(args.port),
            "--dbname", args.dbname,
            "--user", args.user,
            "--password", args.password,
        ])

    print(f"\n{'=' * 70}")
    print(f"WEEKLY UPDATE COMPLETE — Season {season_year}-{season_year + 1}")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
