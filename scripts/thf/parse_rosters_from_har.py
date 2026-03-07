import json
import csv
import sys
import os
from urllib.parse import urlparse, parse_qs

def load_team_mapping(csv_path):
    """Loads team_id to team_name mapping into a dictionary."""
    mapping = {}
    if os.path.exists(csv_path):
        with open(csv_path, 'r', encoding='utf-8') as f:
            reader = csv.DictReader(f)
            for row in reader:
                # Map team_id to team_name
                mapping[row['team_id']] = row['team_name']
    return mapping

def parse_har_to_roster(har_file_path, team_csv_path):
    # Load team mapping first
    team_map = load_team_mapping(team_csv_path)
    
    with open(har_file_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)

    all_players = []
    
    for entry in har_data.get('log', {}).get('entries', []):
        url = entry['request']['url']
        
        if "get_roster" in url:
            parsed_url = urlparse(url)
            query_params = parse_qs(parsed_url.query)
            team_id = query_params.get('team_id', [None])[0]
            
            # Lookup the name from our dictionary
            team_name = team_map.get(team_id, "Unknown Team")
            
            response_content = entry['response']['content'].get('text')
            if response_content:
                try:
                    roster_json = json.loads(response_content)
                    for player in roster_json.get('players', []):
                        player_record = {
                            "team_id": team_id,
                            "team_name": team_name, # Added field
                            "name": player.get("player_name"),
                            "jersey": player.get("jersey"),
                            "position": player.get("position"),
                            "gp": player.get("games_played"),
                            "goals": player.get("goals"),
                            "assists": player.get("assists"),
                            "points": player.get("points")
                        }
                        all_players.append(player_record)
                except json.JSONDecodeError:
                    continue

    # Save output
    if all_players:
        output_name = "roster_with_names.csv"
        keys = all_players[0].keys()
        with open(output_name, 'w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=keys)
            writer.writeheader()
            writer.writerows(all_players)
        print(f"Processed {len(all_players)} players with team names to {output_name}")

if __name__ == "__main__":
    # Usage: python scrape_rosters.py input/2025-all.har input/2025-thf-teams.csv
    har_file = sys.argv[1] if len(sys.argv) > 1 else "your_file_name.har"
    team_csv = sys.argv[2] if len(sys.argv) > 2 else "teams.csv"
    parse_har_to_roster(har_file, team_csv)