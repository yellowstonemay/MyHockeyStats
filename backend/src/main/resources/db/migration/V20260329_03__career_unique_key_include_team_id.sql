-- Include team_id in THF/AHF career uniqueness so one player can have
-- multiple rows in the same season when assigned to multiple teams.

ALTER TABLE IF EXISTS thf_player_career
    ADD COLUMN IF NOT EXISTS team_id VARCHAR(128);

UPDATE thf_player_career
SET team_id = 'UNKNOWN'
WHERE team_id IS NULL;

ALTER TABLE IF EXISTS thf_player_career
    ALTER COLUMN team_id SET NOT NULL;

ALTER TABLE IF EXISTS thf_player_career
    DROP CONSTRAINT IF EXISTS thf_player_career_source_player_id_season_label_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_thf_player_career_player_season_team
    ON thf_player_career (source_player_id, season_label, team_id);

ALTER TABLE IF EXISTS ahf_player_career
    ADD COLUMN IF NOT EXISTS team_id VARCHAR(128);

UPDATE ahf_player_career
SET team_id = 'UNKNOWN'
WHERE team_id IS NULL;

ALTER TABLE IF EXISTS ahf_player_career
    ALTER COLUMN team_id SET NOT NULL;

ALTER TABLE IF EXISTS ahf_player_career
    DROP CONSTRAINT IF EXISTS ahf_player_career_source_player_id_season_label_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ahf_player_career_player_season_team
    ON ahf_player_career (source_player_id, season_label, team_id);
