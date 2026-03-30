-- thf_teams
--   Raw team data scraped from tier1hockeyfederation.com.
--   One row per (season_year, team_id).
--   Populated and maintained by scripts/thf-js/scrape_teams.js.
CREATE TABLE IF NOT EXISTS thf_teams (
    season_year  INTEGER      NOT NULL,
    team_id      VARCHAR(128) NOT NULL,
    team_name    VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (season_year, team_id)
);

CREATE INDEX IF NOT EXISTS idx_thf_teams_season
    ON thf_teams (season_year);

-- ahf_teams
--   Raw team data scraped from atlantichockeyfederation.com.
--   One row per (season_year, team_id).
--   Populated and maintained by scripts/thf-js/scrape_teams.js.
CREATE TABLE IF NOT EXISTS ahf_teams (
    season_year  INTEGER      NOT NULL,
    team_id      VARCHAR(128) NOT NULL,
    team_name    VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (season_year, team_id)
);

CREATE INDEX IF NOT EXISTS idx_ahf_teams_season
    ON ahf_teams (season_year);
