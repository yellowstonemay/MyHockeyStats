-- Social login (Google/Facebook) + multi-player accounts.
--
-- Before: 1 login <-> 1 player profile (player_profiles.user_id was unique),
--         and source identity links / deep-dive requests were keyed by user.
-- After:  a login can manage MANY player profiles, and MANY logins can share
--         the same player profile (e.g. two parents + the player themself).
--         Source identity links and deep-dive requests move to the PLAYER, so
--         a player is refreshed once no matter how many logins point at them.
--
-- IMPORTANT: the base tables (users, player_profiles, deep_dive_requests) are
-- created by Hibernate (ddl-auto=update) or by earlier migrations. On a brand
-- new database Flyway runs BEFORE Hibernate, so there is nothing to alter yet —
-- Hibernate will create the tables directly in the new shape. The whole
-- migration is therefore wrapped in a guard that no-ops on a fresh database and
-- does the real work on an existing one.

DO $$
DECLARE
    c record;
BEGIN
    IF to_regclass('users') IS NULL OR to_regclass('player_profiles') IS NULL THEN
        RAISE NOTICE 'V20260916_01 skipped: base tables not present yet (fresh database)';
        RETURN;
    END IF;

    -- -----------------------------------------------------------------------
    -- 1. Social accounts: a user without a password is allowed.
    -- -----------------------------------------------------------------------
    ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

    CREATE TABLE IF NOT EXISTS oauth_accounts (
        id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id           bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        provider          varchar(32)  NOT NULL,
        provider_user_id  varchar(191) NOT NULL,
        email             varchar(255),
        display_name      varchar(255),
        avatar_url        text,
        created_at        timestamp without time zone NOT NULL DEFAULT now(),
        updated_at        timestamp without time zone NOT NULL DEFAULT now(),
        UNIQUE (provider, provider_user_id)
    );

    CREATE INDEX IF NOT EXISTS idx_oauth_accounts_user ON oauth_accounts (user_id);

    -- -----------------------------------------------------------------------
    -- 2. player_profiles.user_id becomes "created by" (nullable, not unique).
    -- -----------------------------------------------------------------------
    ALTER TABLE player_profiles ALTER COLUMN user_id DROP NOT NULL;

    -- Drop a legacy one-to-one UNIQUE constraint on player_profiles(user_id),
    -- whatever Hibernate happened to name it.
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'player_profiles'::regclass
          AND contype = 'u'
          AND conkey = ARRAY[(
                SELECT attnum FROM pg_attribute
                WHERE attrelid = 'player_profiles'::regclass AND attname = 'user_id'
              )]
    LOOP
        EXECUTE format('ALTER TABLE player_profiles DROP CONSTRAINT %I', c.conname);
    END LOOP;

    -- Same thing when Hibernate created a bare unique index instead.
    FOR c IN
        SELECT indexrelid::regclass AS idxname
        FROM pg_index
        WHERE indrelid = 'player_profiles'::regclass
          AND indisunique
          AND indkey = ARRAY[(
                SELECT attnum FROM pg_attribute
                WHERE attrelid = 'player_profiles'::regclass AND attname = 'user_id'
              )]::int2vector
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %s', c.idxname);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'player_profiles'::regclass
                 AND attname = 'user_id' AND NOT attisdropped) THEN
        ALTER TABLE player_profiles RENAME COLUMN user_id TO created_by_user_id;
    END IF;

    -- -----------------------------------------------------------------------
    -- 3. Many-to-many: which logins can see / manage a player profile.
    -- -----------------------------------------------------------------------
    CREATE TABLE IF NOT EXISTS user_players (
        id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id     bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        player_id   bigint NOT NULL REFERENCES player_profiles(id) ON DELETE CASCADE,
        relation    varchar(32) NOT NULL DEFAULT 'PARENT',  -- SELF | PARENT | GUARDIAN | FAN
        is_primary  boolean NOT NULL DEFAULT false,
        created_at  timestamp without time zone NOT NULL DEFAULT now(),
        UNIQUE (user_id, player_id)
    );

    CREATE INDEX IF NOT EXISTS idx_user_players_user ON user_players (user_id);
    CREATE INDEX IF NOT EXISTS idx_user_players_player ON user_players (player_id);

    -- Backfill: every legacy profile is now linked to the login that created it.
    INSERT INTO user_players (user_id, player_id, relation, is_primary, created_at)
    SELECT pp.created_by_user_id, pp.id, 'SELF', true, COALESCE(pp.created_at, now())
    FROM player_profiles pp
    WHERE pp.created_by_user_id IS NOT NULL
    ON CONFLICT (user_id, player_id) DO NOTHING;

    -- -----------------------------------------------------------------------
    -- 4. Source identity links move from the login to the player.
    -- -----------------------------------------------------------------------
    CREATE TABLE IF NOT EXISTS player_source_links (
        id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        player_id         bigint NOT NULL REFERENCES player_profiles(id) ON DELETE CASCADE,
        source            varchar(32)  NOT NULL,
        source_player_id  varchar(128) NOT NULL,
        link_state        varchar(32)  NOT NULL DEFAULT 'CONFIRMED',
        match_method      varchar(32)  NOT NULL DEFAULT 'AUTO_NAME_MATCH',
        confidence_score  numeric(5,4),
        confirmed_at      timestamp,
        last_verified_at  timestamp,
        created_at        timestamp without time zone NOT NULL DEFAULT now(),
        updated_at        timestamp without time zone NOT NULL DEFAULT now(),
        UNIQUE (player_id, source)
    );

    CREATE INDEX IF NOT EXISTS idx_player_source_links_player
        ON player_source_links (player_id);
    CREATE INDEX IF NOT EXISTS idx_player_source_links_source
        ON player_source_links (source, source_player_id);

    -- Backfill from the legacy user-scoped map.
    IF to_regclass('player_identity_map') IS NOT NULL THEN
        INSERT INTO player_source_links (player_id, source, source_player_id, link_state,
                                         match_method, confidence_score, confirmed_at,
                                         last_verified_at, created_at, updated_at)
        SELECT pp.id, pim.source, pim.source_player_id, pim.link_state,
               pim.match_method, pim.confidence_score, pim.confirmed_at,
               pim.last_verified_at, pim.created_at, pim.updated_at
        FROM player_identity_map pim
        JOIN player_profiles pp ON pp.created_by_user_id = pim.user_id
        ON CONFLICT (player_id, source) DO NOTHING;
    END IF;

    -- -----------------------------------------------------------------------
    -- 5. Deep-dive requests are per PLAYER (deduped across all attached logins).
    -- -----------------------------------------------------------------------
    IF to_regclass('deep_dive_requests') IS NOT NULL THEN
        ALTER TABLE deep_dive_requests
            ADD COLUMN IF NOT EXISTS player_id bigint REFERENCES player_profiles(id) ON DELETE CASCADE;

        UPDATE deep_dive_requests ddr
           SET player_id = pp.id
          FROM player_profiles pp
         WHERE ddr.player_id IS NULL
           AND pp.created_by_user_id = ddr.user_id;

        ALTER TABLE deep_dive_requests ALTER COLUMN user_id DROP NOT NULL;

        CREATE INDEX IF NOT EXISTS idx_deep_dive_requests_player
            ON deep_dive_requests (player_id, status);
    END IF;
END $$;
