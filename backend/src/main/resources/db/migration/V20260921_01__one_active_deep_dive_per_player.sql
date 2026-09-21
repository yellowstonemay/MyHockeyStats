-- At most one in-flight deep-dive per player.
--
-- DeepDiveService.enqueueForPlayer() checks for a PENDING/RUNNING row and then
-- inserts, so two clicks that land close together (double-click, or an admin
-- clicking while the poller is claiming the row) can both pass the check and
-- queue a duplicate all-seasons scrape of the same player. The partial unique
-- index makes that check atomic: the losing INSERT is rejected instead of
-- enqueuing a second run.
--
-- Only PENDING/RUNNING rows are covered, so the request history is untouched.

DO $$
BEGIN
    IF to_regclass('deep_dive_requests') IS NULL THEN
        RAISE NOTICE 'V20260921_01 skipped: deep_dive_requests not present yet';
        RETURN;
    END IF;

    -- Self-healing for databases that already collected duplicates: keep the
    -- oldest in-flight request (the one the poller is most likely already
    -- working on) and close the rest out so the index can be created.
    UPDATE deep_dive_requests d
       SET status = 'FAILED',
           completed_at = NOW(),
           error = 'superseded by a duplicate request for the same player'
     WHERE d.status IN ('PENDING', 'RUNNING')
       AND d.player_id IS NOT NULL
       AND EXISTS (
            SELECT 1
              FROM deep_dive_requests o
             WHERE o.player_id = d.player_id
               AND o.status IN ('PENDING', 'RUNNING')
               AND (o.requested_at, o.id) < (d.requested_at, d.id)
       );

    CREATE UNIQUE INDEX IF NOT EXISTS uq_deep_dive_requests_active_player
        ON deep_dive_requests (player_id)
        WHERE player_id IS NOT NULL AND status IN ('PENDING', 'RUNNING');
END $$;
