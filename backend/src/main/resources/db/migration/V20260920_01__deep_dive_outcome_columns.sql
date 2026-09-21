-- Per-link deep-dive outcome.
--
-- player_source_links.last_verified_at is written by identity_link.py on every
-- run, so it only tells you when a link was last *seen* — not whether the
-- scrape behind it worked. The admin page used it as "Last deep-dive", which
-- stayed fresh even when the scrape failed every single day, so a broken
-- source looked healthy.
--
-- These three columns record what actually happened the last time the link was
-- scraped. The deep-dive scripts write them through
-- deep_dive_status.record_outcome().

DO $$
BEGIN
    IF to_regclass('player_source_links') IS NULL THEN
        RAISE NOTICE 'V20260920_01 skipped: player_source_links not present yet';
        RETURN;
    END IF;

    ALTER TABLE player_source_links
        ADD COLUMN IF NOT EXISTS last_deep_dive_at     timestamp without time zone;
    ALTER TABLE player_source_links
        ADD COLUMN IF NOT EXISTS last_deep_dive_status varchar(16);
    ALTER TABLE player_source_links
        ADD COLUMN IF NOT EXISTS last_deep_dive_error  text;
END $$;

-- Seed the outcome from the scrape tables so the admin page shows a real
-- result immediately instead of "never" for links that have been scraped
-- daily for months. A career row only exists if a scrape succeeded.
DO $$
BEGIN
    IF to_regclass('player_source_links') IS NULL
       OR to_regclass('ayhl_player_career') IS NULL
       OR to_regclass('thf_player_career') IS NULL
       OR to_regclass('ahf_player_career') IS NULL
       OR to_regclass('njhs_player_career') IS NULL THEN
        RAISE NOTICE 'V20260920_01 backfill skipped: scrape tables not present yet';
        RETURN;
    END IF;

    UPDATE player_source_links psl
       SET last_deep_dive_at     = src.last_scraped_at,
           last_deep_dive_status = 'SUCCESS'
      FROM (
            SELECT 'AYHL' AS source, source_player_id, MAX(last_scraped_at) AS last_scraped_at
              FROM ayhl_player_career
             GROUP BY source_player_id
            UNION ALL
            SELECT 'THF', source_player_id, MAX(last_scraped_at)
              FROM thf_player_career
             GROUP BY source_player_id
            UNION ALL
            SELECT 'AHF', source_player_id, MAX(last_scraped_at)
              FROM ahf_player_career
             GROUP BY source_player_id
            UNION ALL
            SELECT 'NJHS', source_player_id, MAX(last_scraped_at)
              FROM njhs_player_career
             GROUP BY source_player_id
           ) src
     WHERE psl.source = src.source
       AND psl.source_player_id = src.source_player_id
       AND psl.last_deep_dive_at IS NULL
       AND src.last_scraped_at IS NOT NULL;
END $$;
