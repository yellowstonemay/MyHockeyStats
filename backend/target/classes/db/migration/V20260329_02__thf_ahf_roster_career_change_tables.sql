-- THF/AHF roster, career, and change-event tables
-- Populated by scripts/thf-js/scrape_rosters.js

CREATE TABLE IF NOT EXISTS thf_rosters (
    season_year  INTEGER       NOT NULL,
    team_id      VARCHAR(128)  NOT NULL,
    team_name    VARCHAR(255),
    player_id    VARCHAR(128)  NOT NULL,
    player_name  VARCHAR(255),
    jersey       VARCHAR(16),
    position     VARCHAR(32),
    birthdate    VARCHAR(32),
    gp           INTEGER,
    goals        INTEGER,
    assists      INTEGER,
    points       INTEGER,
    pims         NUMERIC(10,1),
    ppg          NUMERIC(10,1),
    sog          INTEGER,
    scraped_at   TIMESTAMP     NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (season_year, team_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_thf_rosters_season_team
    ON thf_rosters (season_year, team_id);

CREATE TABLE IF NOT EXISTS ahf_rosters (
    season_year  INTEGER       NOT NULL,
    team_id      VARCHAR(128)  NOT NULL,
    team_name    VARCHAR(255),
    player_id    VARCHAR(128)  NOT NULL,
    player_name  VARCHAR(255),
    jersey       VARCHAR(16),
    position     VARCHAR(32),
    birthdate    VARCHAR(32),
    gp           INTEGER,
    goals        INTEGER,
    assists      INTEGER,
    points       INTEGER,
    pims         NUMERIC(10,1),
    ppg          NUMERIC(10,1),
    sog          INTEGER,
    scraped_at   TIMESTAMP     NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (season_year, team_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_ahf_rosters_season_team
    ON ahf_rosters (season_year, team_id);

CREATE TABLE IF NOT EXISTS thf_player_career (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(255) NOT NULL,
    league_name      VARCHAR(255),
    team_name        VARCHAR(1024),
    jersey_number    VARCHAR(16),
    games_played     INTEGER,
    goals            INTEGER,
    assists          INTEGER,
    points           INTEGER,
    penalties        INTEGER,
    pim              NUMERIC(10,1),
    last_scraped_at  TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    UNIQUE (source_player_id, season_label)
);

CREATE INDEX IF NOT EXISTS idx_thf_career_player_season
    ON thf_player_career (source_player_id, season_label);

CREATE TABLE IF NOT EXISTS ahf_player_career (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(255) NOT NULL,
    league_name      VARCHAR(255),
    team_name        VARCHAR(1024),
    jersey_number    VARCHAR(16),
    games_played     INTEGER,
    goals            INTEGER,
    assists          INTEGER,
    points           INTEGER,
    penalties        INTEGER,
    pim              NUMERIC(10,1),
    last_scraped_at  TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    UNIQUE (source_player_id, season_label)
);

CREATE INDEX IF NOT EXISTS idx_ahf_career_player_season
    ON ahf_player_career (source_player_id, season_label);

CREATE TABLE IF NOT EXISTS thf_change_event (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(255) NOT NULL,
    field_name       VARCHAR(128) NOT NULL,
    old_value        TEXT,
    new_value        TEXT,
    detected_at      TIMESTAMP    NOT NULL,
    event_type       VARCHAR(32)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_thf_change_event_detection
    ON thf_change_event (source_player_id, season_label, detected_at);

CREATE TABLE IF NOT EXISTS ahf_change_event (
    id               UUID         PRIMARY KEY,
    source_player_id VARCHAR(128) NOT NULL,
    player_name      VARCHAR(255),
    season_label     VARCHAR(255) NOT NULL,
    field_name       VARCHAR(128) NOT NULL,
    old_value        TEXT,
    new_value        TEXT,
    detected_at      TIMESTAMP    NOT NULL,
    event_type       VARCHAR(32)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ahf_change_event_detection
    ON ahf_change_event (source_player_id, season_label, detected_at);
