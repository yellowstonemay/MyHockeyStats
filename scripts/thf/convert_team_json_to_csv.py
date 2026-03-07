import json
import csv
import os

# Use forward slashes for better path compatibility
json_path = 'c:/ethan-ai/MyHockeyStats/scripts/thf/input/2025-ahf-teams.json'
csv_path = 'c:/ethan-ai/MyHockeyStats/scripts/thf/input/2025-ahf-teams.csv'

try:
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)
except json.JSONDecodeError as e:
    print(f"Error decoding JSON: {e}")
    print("Please ensure the JSON file is well-formed.")
    exit(1)
except FileNotFoundError:
    print(f"Error: The file was not found at {json_path}")
    exit(1)

teams = data.get('teams', [])

if not teams:
    print("No teams found in the JSON file.")
    exit()

# Extract only the required fields and write to CSV
with open(csv_path, 'w', newline='', encoding='utf-8') as f:
    fieldnames = ['team_id', 'team_name']
    writer = csv.DictWriter(f, fieldnames=fieldnames)
    writer.writeheader()
    
    for team in teams:
        # Ensure both team_id and team_name are present before writing
        if team.get('team_id') and team.get('team_name'):
            writer.writerow({
                'team_id': team.get('team_id'), 
                'team_name': team.get('team_name')
            })

print(f"Successfully converted {len(teams)} teams to {os.path.basename(csv_path)}")
