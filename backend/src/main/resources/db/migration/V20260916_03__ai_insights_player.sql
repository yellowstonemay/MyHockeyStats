-- AI season reports are per player, not per login: a login can own several
-- players (see V20260916_01__social_login_and_multi_player.sql), so the cached
-- insight must say which player it was written for.
ALTER TABLE ai_insights
    ADD COLUMN IF NOT EXISTS player_id bigint REFERENCES player_profiles(id) ON DELETE CASCADE;

-- Existing rows were generated before the switcher existed, i.e. for the
-- login's primary player.
UPDATE ai_insights ai
SET player_id = (
    SELECT up.player_id FROM user_players up
    WHERE up.user_id = ai.user_id
    ORDER BY up.is_primary DESC, up.created_at ASC
    LIMIT 1
)
WHERE ai.player_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ai_insights_user_player
    ON ai_insights (user_id, player_id);
