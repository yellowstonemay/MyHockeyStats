-- Followed players are TRACKED profiles: they exist so the player is selectable
-- in the dashboard and their stats tabs work, but nobody owns them and the
-- sources usually publish no birthdate (thf_rosters / ahf_rosters carry none at
-- all). Without this, following a player could only ever add a name to a list.
ALTER TABLE player_profiles ALTER COLUMN birthdate DROP NOT NULL;
