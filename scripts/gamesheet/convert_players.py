import json
import pandas as pd

def transform_json_to_csv(input_filename, output_filename):
    # 1. Load the data
    with open(input_filename, 'r') as f:
        data = json.load(f)
    
    table_data = data['tableData']
    num_players = len(table_data['names'])
    rows = []

    # 2. Iterate through each player index
    for i in range(num_players):
        player_row = {}
        
        # --- Handle Basic Info (Names) ---
        # tableData['names'][i] contains {firstName, lastName, id, photo}
        player_row.update(table_data['names'][i])
        
        # --- Handle Complex Fields (Lists) ---
        # Handle teamNames (nested list of dicts)
        team_data = table_data['teamNames']['data'][i]
        player_row['teamName'] = team_data[0]['title'] if team_data else None
        player_row['teamId'] = team_data[0]['id'] if team_data else None
        
        # Handle positions (nested list)
        pos_data = table_data['positions']['data'][i]
        player_row['position'] = pos_data[0] if pos_data else None
        
        # --- Handle All Other Stats ---
        # Iterate through remaining keys in tableData
        # We skip keys we've already manually processed
        skip_keys = ['names', 'teamNames', 'positions', 'ids']
        
        for key, value in table_data.items():
            if key not in skip_keys:
                # Most stat keys have a 'data' subkey
                if isinstance(value, dict) and 'data' in value:
                    player_row[key] = value['data'][i]
                # Some keys might just be direct lists (like 'flags')
                elif isinstance(value, list):
                    player_row[key] = value[i]
        
        rows.append(player_row)

    # 3. Create DataFrame and export
    df = pd.DataFrame(rows)
    df.to_csv(output_filename, index=False)
    print(f"Successfully converted {num_players} players to {output_filename}")

# Run the function
transform_json_to_csv('data/6579_players.json', '6579_players.csv')