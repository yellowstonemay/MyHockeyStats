-- Player ownership + manual stat corrections.
--
-- 1. player_profiles.owner_user_id — the single login that owns a player.
--    "Owner" = the account that decides: profile edits, manual G/A entries and
--    who else may edit. Before this column there was no way to tell an owner
--    apart from a login that had merely picked "This is me" in the relation
--    dropdown, so any linked account could write.
-- 2. user_players.can_edit — explicit edit grant. Linking a player is
--    read-only by default; the owner (or an admin) hands out edit rights.
-- 3. game_stat_overrides — G/A/PIM typed by a human, kept in their own table so
--    that re-scraping a source can never clobber them.

CREATE TABLE IF NOT EXISTS game_stat_overrides (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    player_profile_id   bigint       NOT NULL,
    source              varchar(32)  NOT NULL,      -- AYHL | THF | AHF | NJHS | MHR
    season_year         integer      NOT NULL,
    game_id             varchar(64)  NOT NULL,
    goals               integer,
    assists             integer,
    pim                 integer,
    entered_by_user_id  bigint,
    created_at          timestamp without time zone NOT NULL DEFAULT now(),
    updated_at          timestamp without time zone NOT NULL DEFAULT now(),
    UNIQUE (player_profile_id, source, season_year, game_id)
);

CREATE INDEX IF NOT EXISTS idx_game_stat_overrides_player
    ON game_stat_overrides (player_profile_id, season_year);

-- Flyway runs BEFORE Hibernate on a brand-new database, so the parent tables may
-- not exist yet; add the foreign keys only once they do.
DO $$
BEGIN
    IF to_regclass('player_profiles') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_game_stat_overrides_player') THEN
        ALTER TABLE game_stat_overrides
            ADD CONSTRAINT fk_game_stat_overrides_player
            FOREIGN KEY (player_profile_id) REFERENCES player_profiles(id) ON DELETE CASCADE;
    END IF;

    IF to_regclass('users') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_game_stat_overrides_user') THEN
        ALTER TABLE game_stat_overrides
            ADD CONSTRAINT fk_game_stat_overrides_user
            FOREIGN KEY (entered_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('player_profiles') IS NULL OR to_regclass('users') IS NULL THEN
        RAISE NOTICE 'V20260918_01 ownership columns skipped: base tables not present yet';
        RETURN;
    END IF;

    ALTER TABLE player_profiles
        ADD COLUMN IF NOT EXISTS owner_user_id bigint REFERENCES users(id) ON DELETE SET NULL;

    -- Whoever created a profile owns it until an admin transfers it.
    UPDATE player_profiles pp
       SET owner_user_id = pp.created_by_user_id
     WHERE pp.owner_user_id IS NULL
       AND pp.created_by_user_id IS NOT NULL;

    CREATE INDEX IF NOT EXISTS idx_player_profiles_owner
        ON player_profiles (owner_user_id);

    IF to_regclass('user_players') IS NOT NULL THEN
        ALTER TABLE user_players ADD COLUMN IF NOT EXISTS can_edit boolean NOT NULL DEFAULT false;

        -- The owner always keeps edit rights on their own player.
        UPDATE user_players up
           SET can_edit = true
          FROM player_profiles pp
         WHERE pp.id = up.player_id
           AND pp.owner_user_id = up.user_id
           AND up.can_edit = false;
    END IF;
END $$;
