-- DEPRECATED: Preparing for removal of player_identity_map table
-- This table is no longer used. Career lookup is now stateless and runtime-based.
-- Queries will be replaced by CareerLookupService.lookupCareerRecordsByName()
-- 
-- Migration steps:
-- 1. Code references to player_identity_map have been removed
-- 2. Existing data can be archived/exported before table drop
-- 3. Table will be dropped in a future migration after verification period

-- Add deprecated marker comment for DBA visibility
COMMENT ON TABLE player_identity_map IS 'DEPRECATED: No longer used. Marked for removal. Career lookup is now stateless and runtime-based (see CareerLookupService).';

-- Optional: Archive old data before dropping (uncomment if needed)
-- CREATE TABLE IF NOT EXISTS player_identity_map_archive AS SELECT * FROM player_identity_map;
-- TRUNCATE TABLE player_identity_map;
