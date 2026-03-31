-- Add functional indexes on normalized player name for career lookup by name
-- Used by CareerLookupService to support runtime name-based player lookup
-- Normalization: LOWER(TRIM(player_name))

-- AYHL
CREATE INDEX IF NOT EXISTS idx_ayhl_career_normalized_name
    ON ayhl_player_career (LOWER(TRIM(player_name)));

-- THF
CREATE INDEX IF NOT EXISTS idx_thf_career_normalized_name
    ON thf_player_career (LOWER(TRIM(player_name)));

-- AHF
CREATE INDEX IF NOT EXISTS idx_ahf_career_normalized_name
    ON ahf_player_career (LOWER(TRIM(player_name)));
