---
applyTo: "scripts/ayhl/**"
---

# AYHL Weekly / Daily Update Workflow

This document covers the two recurring data-pipeline workflows for keeping AYHL
player rosters and career stats current in the MyHockeyStats database.

---

## Season Calendar

The AYHL season runs **April through March** of the following year.
The "season year" is the April start year (e.g. the season that starts April 2025
is `season_year = 2025`, and its `season_label` is `"2025-2026 Season"`).

Auto-detection logic used by both orchestrator scripts:

```python
# month >= 4  →  season_year = current year
# month 1-3   →  season_year = current year − 1
today = date.today()
season_year = today.year if today.month >= 4 else today.year - 1
season_label = f"{season_year}-{season_year + 1} Season"
```

---

## Weekly Update (run once per week, e.g. every Sunday)

Refreshes the team list and player rosters for the current season and loads
them into the `ayhl_roster` table.

### Steps

1. **Discover leagues and teams**

   ```bash
   python scripts/ayhl/discover_teams.py --season <season_year>
   ```

   Output: `scripts/ayhl/data/teams/<season_year>-ayhl-teams.csv`

2. **Scrape rosters** for every discovered team

   ```bash
   python scripts/ayhl/scrape_rosters.py --season <season_year>
   ```

   Reads: `scripts/ayhl/data/teams/<season_year>-ayhl-teams.csv`
   Output: `scripts/ayhl/data/rosters/<season_year>-ayhl-rosters.csv`

3. **Load rosters to DB** (upserts into `ayhl_roster`)

   ```bash
   python scripts/ayhl/load_rosters_to_db.py \
       --host localhost --port 5432 \
       --dbname myhockeystats --user postgres --password postgres
   ```

### Shortcut — orchestrator script

```bash
# Auto-detect current season
python scripts/ayhl/weekly_update.py

# Override season
python scripts/ayhl/weekly_update.py --season 2025

# Dry-run (scrape only; no DB writes)
python scripts/ayhl/weekly_update.py --dry-run
```

---

## Daily Update (run every day during active season)

Fetches the latest career stats for all current-season players and persists
them to the `ayhl_player_career` table. Detects changes between scrapes and
appends records to `ayhl_change_event`.

### Steps

1. **Query DB** — fetch all `player_id` values from `ayhl_roster`
   where `season_label = '<current label>'`.

2. **Reconcile career JSON** — if any player lacks a season stub in
   `data/ayhl-player-career.json`, run:

   ```bash
   python scripts/ayhl/reconcile_rosters_to_career_json.py \
       --rosters scripts/ayhl/data/rosters/<season_year>-ayhl-rosters.csv \
       --career-json scripts/ayhl/data/ayhl-player-career.json
   ```

3. **Scrape career stats** — write a temp CSV of player IDs and call:

   ```bash
   python scripts/ayhl/scrape_player_careers.py <temp_player_id.csv>
   ```

   Updates `data/ayhl-player-career.json` in place.  
   Scraping is **resumable** — progress is checkpointed to
   `data/ayhl-player-career-progress.json`.

4. **Upsert to DB** — parse `ayhl-player-career.json` for the current
   season entry per player and upsert into `ayhl_player_career`.

5. **Detect changes** — compare new scraped values against the pre-upsert
   DB snapshot; insert changed fields as rows in `ayhl_change_event`.

### Shortcut — orchestrator script

```bash
# Auto-detect current season
python scripts/ayhl/daily_update.py

# Override season
python scripts/ayhl/daily_update.py --season 2025

# Dry-run (scrape but no DB writes)
python scripts/ayhl/daily_update.py --dry-run
```

---

## Database Tables

### `ayhl_roster`

Raw roster data scraped weekly from atlantichockey.org.  
One row per `(season_year, league_id, team_id, player_id)`.  
Managed by `load_rosters_to_db.py` (via `weekly_update.py`).

| Column            | Type         | Notes                                     |
|-------------------|--------------|-------------------------------------------|
| `id`              | UUID         | Primary key                               |
| `season_year`     | INTEGER      | AYHL season start year (e.g. `2025`)      |
| `season_id`       | VARCHAR(32)  | AYHL internal season ID                   |
| `league_id`       | VARCHAR(32)  | AYHL internal league ID                   |
| `team_id`         | VARCHAR(32)  | AYHL internal team ID                     |
| `team_name`       | VARCHAR(255) |                                           |
| `player_id`       | VARCHAR(128) | AYHL numeric player ID                    |
| `player_name_raw` | VARCHAR(255) | Raw "Last, First" string from AYHL         |
| `jersey_number`   | VARCHAR(16)  |                                           |
| `position`        | VARCHAR(16)  |                                           |
| `height`          | VARCHAR(16)  |                                           |
| `weight`          | VARCHAR(16)  |                                           |
| `shoots`          | VARCHAR(8)   |                                           |
| `birth_month`     | INTEGER      |                                           |
| `birth_year`      | INTEGER      |                                           |
| `hometown`        | VARCHAR(255) |                                           |
| `season_label`    | VARCHAR(32)  | e.g. `"2025-2026 Season"`                |
| `scraped_at`      | TIMESTAMP    | UTC time of last successful scrape        |

Flyway migration: `V20260327_02__ayhl_roster_table.sql`

---
### `ayhl_player_career`

One row per `(source_player_id, season_label)`.  
Managed by `daily_update.py`.

| Column            | Type         | Notes                          |
|-------------------|--------------|--------------------------------|
| `id`              | UUID         | Primary key                    |
| `source_player_id`| VARCHAR(128) | AYHL numeric player ID         |
| `player_name`     | VARCHAR(255) |                                |
| `season_label`    | VARCHAR(32)  | e.g. `"2025-2026 Season"`     |
| `league_name`     | VARCHAR(255) |                                |
| `team_name`       | VARCHAR(255) |                                |
| `jersey_number`   | VARCHAR(16)  |                                |
| `games_played`    | INTEGER      | GP                             |
| `goals`           | INTEGER      | G                              |
| `assists`         | INTEGER      | A                              |
| `points`          | INTEGER      | PTS                            |
| `penalties`       | INTEGER      | PEN                            |
| `pim`             | INTEGER      | PIM                            |
| `last_scraped_at` | TIMESTAMP    | UTC time of last successful scrape |
| `created_at`      | TIMESTAMP    |                                |
| `updated_at`      | TIMESTAMP    |                                |

### `ayhl_change_event`

Append-only log. One row per changed field per player per daily run.  
Managed by `daily_update.py`.

| Column            | Type         | Notes                                              |
|-------------------|--------------|----------------------------------------------------|
| `id`              | UUID         | Primary key                                        |
| `source_player_id`| VARCHAR(128) |                                                    |
| `player_name`     | VARCHAR(255) |                                                    |
| `season_label`    | VARCHAR(32)  |                                                    |
| `field_name`      | VARCHAR(128) | DB column that changed                             |
| `old_value`       | TEXT         | Previous value (NULL for NEW_SEASON events)        |
| `new_value`       | TEXT         | New value                                          |
| `detected_at`     | TIMESTAMP    | UTC                                                |
| `event_type`      | VARCHAR(32)  | `NEW_SEASON` \| `STAT_CHANGE` \| `TEAM_CHANGE`  |

Flyway migration: `V20260327_01__ayhl_career_change_tables.sql`

---

## Notes

- Both orchestrators accept `--season <year>` to override the auto-detected
  season year, and `--dry-run` to scrape and analyze without writing to the DB.
- `discover_teams.py` uses **Playwright (Chromium)**.  
  First-time setup: `python -m playwright install chromium`
- `scrape_player_careers.py` is resumable. If interrupted, re-run with the
  same arguments — it will skip already-scraped players using the progress
  checkpoint file.
- Only the **current season** is scraped/synced during daily updates to avoid
  unnecessary re-scraping of historical seasons.
- The weekly update must run before the daily update for a new season, because
   `daily_update.py` reads `ayhl_roster` (with `ayhl_player_career` fallback)
   to build its player list.
- `SEASON_TO_ID` mapping lives in `discover_teams.py`. When a new season
  becomes available, add `<year>: <id>` to the top of the dict.

---

## Common CLI Reference

```bash
# Weekly (full pipeline)
python scripts/ayhl/weekly_update.py --season 2025

# Daily (career stats sync)
python scripts/ayhl/daily_update.py --season 2025

# Individual steps
python scripts/ayhl/discover_teams.py --season 2025
python scripts/ayhl/scrape_rosters.py --season 2025
python scripts/ayhl/load_rosters_to_db.py
python scripts/ayhl/reconcile_rosters_to_career_json.py \
    --rosters scripts/ayhl/data/rosters/2025-ayhl-rosters.csv \
    --career-json scripts/ayhl/data/ayhl-player-career.json
python scripts/ayhl/scrape_player_careers.py <player_id_csv>
```
