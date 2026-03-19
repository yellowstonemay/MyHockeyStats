"""Convert ayhl-player-career.csv to ayhl-player-career.json indexed by playerid.

Usage:
    python convert_player_career_csv_to_json.py
    python convert_player_career_csv_to_json.py --input data/ayhl-player-career.csv --output data/ayhl-player-career.json
"""

import argparse
import csv
import json
import os


def parse_args():
    parser = argparse.ArgumentParser(description="Convert player career CSV to JSON keyed by playerid")
    parser.add_argument("--input", default="data/ayhl-player-career.csv", help="Input CSV path")
    parser.add_argument("--output", default="data/ayhl-player-career.json", help="Output JSON path")
    return parser.parse_args()


def main():
    args = parse_args()
    input_path = args.input
    output_path = args.output

    if not os.path.exists(input_path):
        raise FileNotFoundError(f"Input CSV not found: {input_path}")

    data = {}
    with open(input_path, "r", encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        fieldnames = reader.fieldnames or []
        fixed_prefix = ["playerid", "player_name", "birthday", "hometown", "position", "shoots"]
        career_columns = [name for name in fieldnames if name not in fixed_prefix]

        for row in reader:
            pid = (row.get("playerid") or "").strip()
            if not pid:
                continue

            if pid not in data:
                data[pid] = {
                    "player_name": (row.get("player_name") or "").strip(),
                    "birthday": (row.get("birthday") or "").strip(),
                    "hometown": (row.get("hometown") or "").strip(),
                    "position": (row.get("position") or "").strip(),
                    "shoots": (row.get("shoots") or "").strip(),
                    "career": [],
                }

            entry = {}
            for col in career_columns:
                entry[col] = (row.get(col) or "").strip()
            data[pid]["career"].append(entry)

    with open(output_path, "w", encoding="utf-8") as out:
        json.dump(data, out, indent=2, ensure_ascii=False)

    print(f"Converted {input_path} -> {output_path}")
    print(f"Players in JSON: {len(data)}")


if __name__ == "__main__":
    main()
