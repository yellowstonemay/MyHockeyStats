-- ayhl_player_career
--   One row per (source_player_id, season_label).
--   Populated and maintained by scripts/ayhl/daily_update.py.
CREATE TABLE IF NOT EXISTS ayhl_player_career (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(32)  NOT NULL,          -- e.g. "2025-2026 Season"
    league_name      VARCHAR(255),
    team_name        VARCHAR(255),
    jersey_number    VARCHAR(16),
    games_played     INTEGER,
    goals            INTEGER,
    assists          INTEGER,
    points           INTEGER,
    penalties        INTEGER,
    pim              INTEGER,
    last_scraped_at  TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    UNIQUE (source_player_id, season_label)
);

-- ayhl_change_event
--   Append-only log of every value change detected between daily scrapes.
--   Populated by scripts/ayhl/daily_update.py.
--   event_type values: NEW_SEASON | STAT_CHANGE | TEAM_CHANGE
CREATE TABLE IF NOT EXISTS ayhl_change_event (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(32)  NOT NULL,
    field_name       VARCHAR(128) NOT NULL,
    old_value        TEXT,
    new_value        TEXT,
    detected_at      TIMESTAMP    NOT NULL,
    event_type       VARCHAR(32)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ayhl_career_player_season
    ON ayhl_player_career (source_player_id, season_label);

CREATE INDEX IF NOT EXISTS idx_ayhl_change_event_detection
    ON ayhl_change_event (source_player_id, season_label, detected_at);
