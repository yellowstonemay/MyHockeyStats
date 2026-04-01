-- ayhl_player_games
--   Per-game statistics for every AYHL player, by season.
--   One row per (player_id, season_year, game_id).
--   Populated by scripts/ayhl/scrape_player_games.py.
CREATE TABLE IF NOT EXISTS ayhl_player_games (
    id              UUID            PRIMARY KEY,
    player_id       VARCHAR(128)    NOT NULL,       -- AYHL numeric player ID
    player_name     VARCHAR(255),
    season_year     INTEGER         NOT NULL,       -- AYHL season start year (e.g. 2025)
    season_label    VARCHAR(32)     NOT NULL,       -- e.g. "2025-2026 Season"
    season_id       VARCHAR(32)     NOT NULL,       -- AYHL internal season ID
    game_id         VARCHAR(32)     NOT NULL,       -- AYHL game ID from table
    game_date       DATE,
    game_type       VARCHAR(128),
    league          VARCHAR(255),
    team_for        VARCHAR(255),                   -- player's team
    team_against    VARCHAR(255),                   -- opponent
    goals           INTEGER,
    goals_pp        INTEGER,                        -- power play goals  G(PP)
    goals_sh        INTEGER,                        -- short-handed goals G(SH)
    goals_so        INTEGER,                        -- shootout goals    G(SO)
    assists         INTEGER,
    points          INTEGER,
    pim             INTEGER,                        -- penalty minutes
    scraped_at      TIMESTAMP       NOT NULL,
    UNIQUE (player_id, season_year, game_id)
);

CREATE INDEX IF NOT EXISTS idx_ayhl_player_games_player_season
    ON ayhl_player_games (player_id, season_year);

CREATE INDEX IF NOT EXISTS idx_ayhl_player_games_season
    ON ayhl_player_games (season_year);
