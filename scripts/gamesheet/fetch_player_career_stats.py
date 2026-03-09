import json
import requests
import pandas as pd
import time
import os
from pathlib import Path

# base season id used in endpoints (6579 is for 2023-2024 season)
SEASON_ID = 6579

# endpoint templates
CAREER_YEARS_URL = f"https://gamesheetstats.com/api/usePlayerCareer/{SEASON_ID}/getStats/{{player_id}}"
YEAR_STATS_URL = f"https://gamesheetstats.com/api/usePlayerCareer/{SEASON_ID}/getStats/{{player_id}}?year={{year}}"

# Configuration
REQUEST_TIMEOUT = 30  # seconds
REQUEST_DELAY = 0.3  # seconds between requests
MAX_RETRIES = 3
RETRY_BACKOFF = 2  # exponential backoff multiplier


def load_player_list(json_path):
    """Return a list of dicts containing at least player id and name.

    The input file may be a JSON produced by the Gamesheet API (with
    ``tableData.names`` fields) or a CSV containing an ``id``/``player_id``
    column (optionally ``firstName``/``lastName`` or similar headers).
    """
    # simple heuristic based on extension; fallback to guessing by trying both
    if json_path.lower().endswith(".csv"):
        df = pd.read_csv(json_path)
        players = []
        for _, row in df.iterrows():
            pid = row.get("id") if "id" in row else row.get("player_id")
            players.append({
                "id": pid,
                "firstName": row.get("firstName") or row.get("first_name"),
                "lastName": row.get("lastName") or row.get("last_name"),
            })
        return players

    # otherwise treat as JSON
    with open(json_path, "r") as f:
        data = json.load(f)

    table = data.get("tableData", {})
    names = table.get("names", [])
    # build simple list of player info
    players = []
    for n in names:
        players.append({
            "id": n.get("id"),
            "firstName": n.get("firstName"),
            "lastName": n.get("lastName"),
        })
    return players


def fetch_with_retry(url, retries=MAX_RETRIES):
    """Fetch URL with exponential backoff retry logic."""
    for attempt in range(retries):
        try:
            resp = requests.get(url, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        except requests.Timeout:
            if attempt < retries - 1:
                wait = REQUEST_DELAY * (RETRY_BACKOFF ** attempt)
                print(f"    timeout, retrying in {wait:.1f}s...", flush=True)
                time.sleep(wait)
            else:
                raise
        except requests.RequestException as e:
            if attempt < retries - 1:
                wait = REQUEST_DELAY * (RETRY_BACKOFF ** attempt)
                print(f"    request error ({e}), retrying in {wait:.1f}s...", flush=True)
                time.sleep(wait)
            else:
                raise
    raise requests.RequestException("Max retries exceeded")


def fetch_years_for_player(player_id):
    """Fetch the list of available years for the given player."""
    url = CAREER_YEARS_URL.format(player_id=player_id)
    data = fetch_with_retry(url)
    return data.get("years", [])


def fetch_stats_for_player_year(player_id, year):
    """Fetch the detailed stats for a player in a specific year."""
    url = YEAR_STATS_URL.format(player_id=player_id, year=year)
    data = fetch_with_retry(url)
    return data.get("playerYearStats", {})


def normalize_year_stats(player_id, year, stats):
    """Turn the year stats structure into a list of flat rows."""
    rows = []
    # determine longest array length
    max_len = 0
    for v in stats.values():
        if isinstance(v, list):
            max_len = max(max_len, len(v))
    
    for idx in range(max_len):
        row = {"player_id": player_id, "season_year": year, "player_name": ""}
        for key, arr in stats.items():
            # attempt to get element at idx; if missing, leave blank
            try:
                entry = arr[idx]
            except (IndexError, TypeError):
                row[key] = None
                continue

            if isinstance(entry, dict) and "data" in entry:
                val = entry["data"]
            else:
                val = entry

            # if the value is a list, try to flatten it
            if isinstance(val, list):
                # convert list of simple values or dicts to string
                flattened = []
                for item in val:
                    if isinstance(item, dict) and "title" in item:
                        flattened.append(item["title"])
                    else:
                        flattened.append(str(item))
                row[key] = ",".join(flattened)
            elif isinstance(val, dict) and "title" in val:
                # extract just the title from dict values (e.g., season info)
                row[key] = val["title"]
            else:
                row[key] = val

        # skip total rows (often indicated in the season or team fields)
        season_val = row.get("season")
        team_val = row.get("team")
        if season_val == "Total" or team_val == "Total":
            continue

        rows.append(row)
    return rows


def build_dataset(json_path, output_csv):
    """Build dataset with checkpointing and incremental CSV writing."""
    players = load_player_list(json_path)
    total_players = len(players)
    
    # Setup checkpoint and error log files
    checkpoint_file = output_csv + ".checkpoint"
    error_log_file = output_csv + ".errors.log"
    failed_players_file = output_csv + ".failed_players.csv"
    
    # Load completed player IDs from checkpoint
    completed_ids = set()
    if os.path.exists(checkpoint_file):
        with open(checkpoint_file, "r") as f:
            completed_ids = set(line.strip() for line in f if line.strip())
        print(f"Resuming: {len(completed_ids)} players already completed")
    
    # Check if output CSV exists to determine write mode
    csv_exists = os.path.exists(output_csv)
    write_mode = "a" if csv_exists else "w"
    write_header = not csv_exists
    
    # Open files for incremental writing
    error_log = open(error_log_file, "a")
    checkpoint_log = open(checkpoint_file, "a")
    
    # Track failed players
    failed_players = []
    
    processed_count = len(completed_ids)
    success_count = 0
    
    try:
        for idx, p in enumerate(players, start=1):
            pid = p.get("id")
            if pid is None:
                continue
            
            # Skip if already processed
            if str(pid) in completed_ids:
                continue
            
            first_name = p.get("firstName") or ""
            last_name = p.get("lastName") or ""
            player_name = f"{first_name} {last_name}".strip() or f"ID={pid}"
            
            print(f"[{processed_count + 1}/{total_players}] Processing {player_name}...", end=" ", flush=True)
            
            player_rows = []
            player_has_errors = False
            try:
                years = fetch_years_for_player(pid)
                print(f"found {len(years)} season(s)", flush=True)
                
                for yr in years:
                    try:
                        stats = fetch_stats_for_player_year(pid, yr)
                        rows = normalize_year_stats(pid, yr, stats)
                        
                        # Attach player name
                        for r in rows:
                            r["firstName"] = p.get("firstName")
                            r["lastName"] = p.get("lastName")
                            r["player_name"] = player_name
                        
                        player_rows.extend(rows)
                        
                        # Be polite to the server
                        time.sleep(REQUEST_DELAY)
                    except Exception as e:
                        error_msg = f"Error fetching stats for player {pid} year {yr}: {e}\n"
                        print(f"  {error_msg.strip()}", flush=True)
                        error_log.write(error_msg)
                        error_log.flush()
                        player_has_errors = True
                
                # Write player's rows incrementally to CSV
                if player_rows:
                    df = pd.DataFrame(player_rows)
                    df.to_csv(output_csv, mode=write_mode, header=write_header, index=False)
                    write_header = False  # Only write header once
                    write_mode = "a"  # Append after first write
                    success_count += 1
                
                # If player had partial errors, add to failed list for potential retry
                if player_has_errors:
                    failed_players.append({
                        "id": pid,
                        "firstName": p.get("firstName"),
                        "lastName": p.get("lastName"),
                        "error": "Partial failure - some years failed"
                    })
                
                # Mark as completed in checkpoint
                checkpoint_log.write(f"{pid}\n")
                checkpoint_log.flush()
                processed_count += 1
                
            except Exception as e:
                error_msg = f"Error fetching years for player {pid} ({player_name}): {e}\n"
                print(f"  {error_msg.strip()}", flush=True)
                error_log.write(error_msg)
                error_log.flush()
                
                # Add to failed players list
                failed_players.append({
                    "id": pid,
                    "firstName": p.get("firstName"),
                    "lastName": p.get("lastName"),
                    "error": str(e)
                })
                processed_count += 1
    
    finally:
        error_log.close()
        checkpoint_log.close()
        
        # Write failed players to CSV for retry
        if failed_players:
            failed_df = pd.DataFrame(failed_players)
            failed_df.to_csv(failed_players_file, index=False)
    
    print(f"\n{'='*60}")
    print(f"Completed! Processed {processed_count} players, {success_count} with data")
    if failed_players:
        print(f"Failed: {len(failed_players)} players (saved to {failed_players_file})")
    print(f"Output: {output_csv}")
    print(f"Errors logged to: {error_log_file}")
    print(f"Checkpoint saved to: {checkpoint_file}")
    if failed_players:
        print(f"\nTo retry failed players only:")
        print(f"  python {os.path.basename(__file__)} {failed_players_file} retry_output.csv")
    print(f"{'='*60}")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Fetch Gamesheet player career stats and dump to CSV.")
    parser.add_argument(
        "input_json",
        help="path to JSON or CSV player list (e.g. 6579_players.json or 6579_players.csv)",
    )
    parser.add_argument("output_csv", help="output CSV path")
    args = parser.parse_args()

    build_dataset(args.input_json, args.output_csv)
