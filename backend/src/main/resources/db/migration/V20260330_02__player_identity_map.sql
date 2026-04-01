-- Stable user-to-source identity links for career-table based integrations.

CREATE TABLE IF NOT EXISTS player_identity_map (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    source VARCHAR(32) NOT NULL,
    source_player_id VARCHAR(128) NOT NULL,
    link_state VARCHAR(32) NOT NULL,
    match_method VARCHAR(32) NOT NULL,
    confidence_score NUMERIC(5,4),
    confirmed_at TIMESTAMP,
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, source)
);

CREATE INDEX IF NOT EXISTS idx_player_identity_map_user_source
    ON player_identity_map (user_id, source);

CREATE INDEX IF NOT EXISTS idx_player_identity_map_source_player
    ON player_identity_map (source, source_player_id);
