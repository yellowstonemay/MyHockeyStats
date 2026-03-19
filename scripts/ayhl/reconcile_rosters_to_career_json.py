"""Reconcile AYHL roster CSV data into career JSON keyed by playerid.

Features:
1. Input roster CSV (yyyy-ayhl-rosters.csv) + career JSON (ayhl-player-career.json).
2. Walk row-by-row to get playerid.
3. Create missing player entries in career JSON.
4. Ensure each player's season exists in career[]; if missing, create from roster row.
5. Optional: for each player, invoke scrape_player_careers.py to fetch latest career data.

Usage examples:
    python reconcile_rosters_to_career_json.py \
        --rosters data/rosters/2025-ayhl-rosters.csv \
        --career-json data/ayhl-player-career.json

    python reconcile_rosters_to_career_json.py \
        --rosters data/rosters/2025-ayhl-rosters.csv \
        --career-json data/ayhl-player-career.json \
        --refresh-via-scraper
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import tempfile
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Reconcile roster CSV into career JSON")
    parser.add_argument("--rosters", required=True, help="Path to yyyy-ayhl-rosters.csv")
    parser.add_argument(
        "--career-json",
        default="data/ayhl-player-career.json",
        help="Path to ayhl-player-career.json (or ayhl-player-carreer.json)",
    )
    parser.add_argument(
        "--progress-json",
        default="data/ayhl-player-career-progress.json",
        help="Path to progress JSON used by scrape_player_careers.py",
    )
    parser.add_argument(
        "--refresh-via-scraper",
        action="store_true",
        help="For each player in roster CSV, call scrape_player_careers.py to refresh latest data",
    )
    parser.add_argument(
        "--scraper-script",
        default="scrape_player_careers.py",
        help="Path to scraper script (default: scrape_player_careers.py)",
    )
    parser.add_argument(
        "--python-exe",
        default=sys.executable,
        help="Python executable used to invoke scraper script",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Analyze and print planned changes without writing files",
    )
    return parser.parse_args()


def load_json_or_default(path: str, default: Any) -> Any:
    if not os.path.exists(path):
        return default
    try:
        with open(path, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
            if isinstance(default, dict) and isinstance(payload, dict):
                return payload
            return default
    except Exception:
        return default


def save_json(path: str, payload: Any) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)


def season_label_from_row(row: dict[str, str]) -> str:
    raw = (row.get("season_year") or "").strip()
    if raw.isdigit():
        year = int(raw)
        return f"{year}-{year + 1} Season"
    return raw


def normalize_text(value: str | None) -> str:
    return (value or "").strip()


def has_season(career_rows: list[dict[str, Any]], season_label: str) -> bool:
    season_norm = normalize_text(season_label).lower()
    if not season_norm:
        return False
    for row in career_rows:
        existing = normalize_text(str(row.get("Season", ""))).lower()
        if existing == season_norm:
            return True
    return False


def ensure_progress_schema(progress: dict[str, Any]) -> dict[str, Any]:
    if "current_player_id" not in progress:
        progress["current_player_id"] = None
    if "completed_player_ids" not in progress or not isinstance(progress["completed_player_ids"], list):
        progress["completed_player_ids"] = []
    if "empty_carrer_ids" not in progress or not isinstance(progress["empty_carrer_ids"], list):
        progress["empty_carrer_ids"] = []
    if "need_retry_ids" not in progress or not isinstance(progress["need_retry_ids"], list):
        progress["need_retry_ids"] = []
    return progress


def remove_from_progress_lists(progress: dict[str, Any], player_id: str) -> None:
    for key in ("completed_player_ids", "empty_carrer_ids"):
        progress[key] = [pid for pid in progress.get(key, []) if str(pid) != player_id]


def force_refresh_one_player(
    player_id: str,
    career_json_path: str,
    progress_json_path: str,
    scraper_script_path: str,
    python_exe: str,
) -> tuple[bool, str]:
    # Remove from career JSON and from completion/empty lists so scraper will fetch again.
    career_data = load_json_or_default(career_json_path, {})
    if player_id in career_data:
        del career_data[player_id]
    save_json(career_json_path, career_data)

    progress = ensure_progress_schema(load_json_or_default(progress_json_path, {}))
    remove_from_progress_lists(progress, player_id)
    progress["current_player_id"] = None
    save_json(progress_json_path, progress)

    scraper_abs = os.path.abspath(scraper_script_path)
    scraper_dir = os.path.dirname(scraper_abs)

    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".txt", encoding="utf-8") as tf:
        tf.write(player_id + "\n")
        temp_input = tf.name

    try:
        proc = subprocess.run(
            [python_exe, scraper_abs, temp_input],
            cwd=scraper_dir,
            capture_output=True,
            text=True,
            check=False,
        )
        ok = proc.returncode == 0
        output = (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")
        return ok, output.strip()
    finally:
        try:
            os.remove(temp_input)
        except OSError:
            pass


def main() -> int:
    args = parse_args()

    rosters_path = os.path.abspath(args.rosters)
    career_json_path = os.path.abspath(args.career_json)
    progress_json_path = os.path.abspath(args.progress_json)

    if not os.path.exists(rosters_path):
        print(f"Roster CSV not found: {rosters_path}")
        return 1

    # Support both spellings if caller passes non-existent carreer path.
    if not os.path.exists(career_json_path):
        alt = career_json_path.replace("carreer", "career") if "carreer" in career_json_path else career_json_path.replace("career", "carreer")
        if alt != career_json_path and os.path.exists(alt):
            career_json_path = alt

    career_data: dict[str, Any] = load_json_or_default(career_json_path, {})

    unique_players: set[str] = set()
    row_count = 0
    created_players = 0
    created_seasons = 0

    with open(rosters_path, "r", encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            row_count += 1
            player_id = normalize_text(row.get("playerid"))
            if not player_id:
                continue

            unique_players.add(player_id)

            player_name = normalize_text(row.get("player"))
            birthday = normalize_text(row.get("birthdate"))
            hometown = normalize_text(row.get("hometown"))
            position = normalize_text(row.get("pos"))
            shoots = normalize_text(row.get("shot"))
            team_name = normalize_text(row.get("team") or row.get("team_name"))
            league_name = normalize_text(row.get("league_name") or row.get("leagueid"))
            season_label = season_label_from_row(row)

            if player_id not in career_data:
                career_data[player_id] = {
                    "player_name": player_name,
                    "birthday": birthday,
                    "hometown": hometown,
                    "position": position,
                    "shoots": shoots,
                    "career": [],
                }
                created_players += 1

            player_obj = career_data[player_id]
            if not normalize_text(player_obj.get("player_name")) and player_name:
                player_obj["player_name"] = player_name
            if not normalize_text(player_obj.get("birthday")) and birthday:
                player_obj["birthday"] = birthday
            if not normalize_text(player_obj.get("hometown")) and hometown:
                player_obj["hometown"] = hometown
            if not normalize_text(player_obj.get("position")) and position:
                player_obj["position"] = position
            if not normalize_text(player_obj.get("shoots")) and shoots:
                player_obj["shoots"] = shoots

            career_rows = player_obj.get("career")
            if not isinstance(career_rows, list):
                career_rows = []
                player_obj["career"] = career_rows

            if season_label and not has_season(career_rows, season_label):
                career_rows.append(
                    {
                        "Season": season_label,
                        "League": league_name,
                        "Teams": team_name,
                        "Games": "",
                        "Goals": "",
                        "Assists": "",
                        "Points": "",
                        "Penalties": "",
                        "PIM": "",
                        "RosterRow": dict(row),
                    }
                )
                created_seasons += 1

    print("=" * 70)
    print("ROSTER -> CAREER JSON RECONCILE")
    print("=" * 70)
    print(f"Roster rows read:      {row_count}")
    print(f"Unique players found:  {len(unique_players)}")
    print(f"New players created:   {created_players}")
    print(f"New seasons appended:  {created_seasons}")
    print(f"Career JSON path:      {career_json_path}")

    if args.dry_run:
        print("Dry-run enabled: no file changes written.")
        return 0

    save_json(career_json_path, career_data)
    print("Career JSON updated.")

    if args.refresh_via_scraper:
        scraper_path = os.path.abspath(args.scraper_script)
        if not os.path.exists(scraper_path):
            print(f"Scraper script not found: {scraper_path}")
            return 1

        refreshed_ok = 0
        refreshed_fail = 0
        print("\nRefreshing latest career data via scraper (per player)...")
        players_sorted = sorted(unique_players, key=lambda x: int(x) if x.isdigit() else x)

        for idx, player_id in enumerate(players_sorted, start=1):
            ok, output = force_refresh_one_player(
                player_id=player_id,
                career_json_path=career_json_path,
                progress_json_path=progress_json_path,
                scraper_script_path=scraper_path,
                python_exe=args.python_exe,
            )
            if ok:
                refreshed_ok += 1
                print(f"  [{idx}/{len(players_sorted)}] OK player_id={player_id}")
            else:
                refreshed_fail += 1
                print(f"  [{idx}/{len(players_sorted)}] FAIL player_id={player_id}")
                if output:
                    short = output.strip().splitlines()
                    print("    " + short[-1][:220])

        print("\nRefresh summary:")
        print(f"  Success: {refreshed_ok}")
        print(f"  Failed:  {refreshed_fail}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
