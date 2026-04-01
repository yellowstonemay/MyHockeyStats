-- ayhl_roster
--   Raw player roster data scraped from atlantichockey.org each week.
--   One row per (season_year, league_id, team_id, player_id).
--   Populated and maintained by scripts/ayhl/load_rosters_to_db.py
--   (called via weekly_update.py).
CREATE TABLE IF NOT EXISTS ayhl_roster (
    id              UUID            PRIMARY KEY,
    season_year     INTEGER         NOT NULL,       -- AYHL season start year (e.g. 2025)
    season_id       VARCHAR(32),                    -- AYHL internal season ID
    league_id       VARCHAR(32),                    -- AYHL internal league ID
    team_id         VARCHAR(32),                    -- AYHL internal team ID
    team_name       VARCHAR(255),
    player_id       VARCHAR(128),                   -- AYHL numeric player ID
    player_name_raw VARCHAR(255)    NOT NULL,        -- raw "Last, First" string from AYHL
    jersey_number   VARCHAR(16),
    position        VARCHAR(16),
    height          VARCHAR(16),
    weight          VARCHAR(16),
    shoots          VARCHAR(8),
    birth_month     INTEGER,
    birth_year      INTEGER,
    hometown        VARCHAR(255),
    season_label    VARCHAR(32)     NOT NULL,        -- e.g. "2025-2026 Season"
    scraped_at      TIMESTAMP       NOT NULL,
    UNIQUE (season_year, league_id, team_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_ayhl_roster_season_player
    ON ayhl_roster (season_label, player_id);
