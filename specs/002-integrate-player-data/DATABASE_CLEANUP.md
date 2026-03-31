# Database Cleanup & Deprecation Guide

**Feature**: 002-integrate-player-data (Simplified Player Data Integration)  
**Migration Phase**: Post-implementation  
**Last Updated**: 2026-03-31

---

## Overview

The simplified player data integration feature (002) removes the persistent `player_identity_map` table and related database artifacts from the old architecture. This document provides step-by-step guidance for safe removal in production environments.

### What's Being Removed

1. **`player_identity_map` table** - Persistent player-career record linking (no longer needed with runtime name matching)
2. **`integration_match_link` table** - Candidate confirmation state (deprecated with new stateless design)
3. **`integration_imported_player_record` table** - Imported player holdings (consolidated into raw career tables)
4. **Related indexes** - Old query optimization on identity_map

### What's Staying

1. **`ayhl_player_career`, `thf_player_career`, `ahf_player_career` tables** - Career data remains unchanged
2. **`player` table** - Player profiles remain unchanged
3. **`account_link` table** - Parent-child relationships remain for authorization
4. **New indexes** - Optimized indexes on normalized player names (added in V20260331_01)

---

## Pre-Cleanup Checklist

### 1. Backup Production Database

```bash
# Create full backup
pg_dump -h <production-host> -U <username> -d hockey_stats \
  --format=custom --file=hockey_stats_backup_$(date +%Y%m%d_%H%M%S).dump

# Verify backup (do NOT proceed until verified)
pg_restore --list hockey_stats_backup_*.dump | head -20
```

### 2. Verify New System is Working

```bash
# Test in staging environment
curl -H "Authorization: Bearer <token>" \
  "https://staging.myhockeystats.com/api/players/{playerId}/seasons"

# Verify response format matches SeasonsResponseDto
# Expected HTTP 200 with:
# {
#   "playerId": "...",
#   "playerName": "...",
#   "records": [...],
#   "hasAmbiguity": false,
#   "availableSources": ["AYHL", "THF"],
#   "emptySources": ["AHF"]
# }
```

### 3. Audit Current Data Usage

```sql
-- Count records in tables being dropped
SELECT COUNT(*) as identity_map_count FROM player_identity_map;
SELECT COUNT(*) as match_link_count FROM integration_match_link;
SELECT COUNT(*) as imported_player_count FROM integration_imported_player_record;

-- These should give you baseline counts before cleanup
-- Save these numbers for your cleanup report
```

### 4. Check for Active Code References

```bash
# Search codebase for references to deprecated tables
grep -r "player_identity_map" backend/src/main/java/ --include="*.java"
grep -r "integration_match_link" backend/src/main/java/ --include="*.java"
grep -r "integration_imported_player_record" backend/src/main/java/ --include="*.java"

# Result should be EMPTY - if not, defer cleanup until code is updated
```

---

## Cleanup Process

### Option A: Automatic Migration (Recommended)

The cleanup migrations are already included in the flyway migration sequence:

```
V20260330_01__drop_unused_integration_tables.sql
V20260330_02__player_identity_map.sql
V20260330_03__drop_integration_match_link.sql
V20260330_04__drop_integration_imported_player_record.sql
```

These will execute automatically on next deployment with:

```bash
# Backend build triggers Flyway migrations
mvn clean install

# Or Docker deployment
docker-compose up --build
```

**Verification after automatic migration**:
```sql
-- Verify tables are gone
SELECT tablename FROM pg_tables 
WHERE schemaname = 'public' 
AND tablename IN ('player_identity_map', 'integration_match_link', 'integration_imported_player_record');

-- Result should be EMPTY
```

### Option B: Manual Cleanup (If Migrations Not Applied)

If for any reason the migrations were skipped, execute manually:

```sql
-- BACKUP FIRST! (see Pre-Cleanup Checklist section 1)

-- 1. Drop foreign keys (if any)
ALTER TABLE IF EXISTS integration_match_link 
DROP CONSTRAINT IF EXISTS fk_player_identity_map_id;

-- 2. Drop indices
DROP INDEX IF EXISTS idx_player_identity_map_normalized_name;
DROP INDEX IF EXISTS idx_match_link_by_player;

-- 3. Drop tables in order (dependent first)
DROP TABLE IF EXISTS integration_imported_player_record;
DROP TABLE IF EXISTS integration_match_link;
DROP TABLE IF EXISTS player_identity_map;

-- 4. Verify
SELECT COUNT(*) as remaining_tables FROM information_schema.tables 
WHERE table_schema = 'public' 
AND table_name IN ('player_identity_map', 'integration_match_link', 'integration_imported_player_record');
-- Result: 0 rows means success
```

---

## Post-Cleanup Validation

### 1. Application Health Check

After cleanup, verify the application still works:

```bash
# Restart application
systemctl restart myhokeystats

# Check logs for errors
tail -f /var/log/myhokeystats/application.log

# Watch for: "Flyway migration", "player_identity_map", "dropped"
```

### 2. Endpoint Functionality Test

```bash
# Test player viewing own seasons
curl -H "Authorization: Bearer <player-token>" \
  "https://api.myhockeystats.com/api/players/{playerId}/seasons"

# Test parent viewing child seasons
curl -H "Authorization: Bearer <parent-token>" \
  "https://api.myhockeystats.com/api/players/{childPlayerId}/seasons"

# Test authorization denial for non-linked user
curl -H "Authorization: Bearer <other-token>" \
  "https://api.myhockeystats.com/api/players/{playerId}/seasons"
# Expected: 401 Unauthorized
```

### 3. Performance Verification

Monitor query performance after cleanup:

```sql
-- Check query performance on new indexes
EXPLAIN ANALYZE
SELECT * FROM ayhl_player_career 
WHERE LOWER(TRIM(player_name)) = 'john doe' 
ORDER BY season DESC;

-- Expected: ~5-10ms for typical queries
-- If > 100ms, consider adding additional indexes
```

### 4. Data Integrity Verification

```sql
-- Verify account_link records still reference valid players
SELECT al.id, al.parent_user_id, al.child_player_id
FROM account_link al
WHERE NOT EXISTS (SELECT 1 FROM player p WHERE p.id = al.parent_user_id)
   OR NOT EXISTS (SELECT 1 FROM player p WHERE p.id = al.child_player_id);

-- Result should be EMPTY - if not, investigate orphaned links
```

---

## Rollback Procedure

**If anything goes wrong during cleanup:**

### 1. Restore from backup (if done recently)

```bash
# STOP APPLICATION FIRST
systemctl stop myhokeystats

# Restore database
pg_restore -h <host> -U <user> -d hockey_stats \
  --clean --if-exists hockey_stats_backup_YYYYMMDD_HHMMSS.dump

# RESTART APPLICATION
systemctl start myhokeystats

# Verify with API test
curl -H "Authorization: Bearer <token>" \
  "https://api.myhockeystats.com/api/players/{playerId}/seasons"
```

### 2. If automatic migrations ran

To rollback to previous schema state:

```bash
# Edit Flyway migration history
DELETE FROM flyway_schema_history 
WHERE script LIKE 'V202603%__drop%' 
ORDER BY installed_rank DESC 
LIMIT 4;

# Manually restore table structure (keep a schema export from pre-cleanup)
psql -h <host> -U <user> -d hockey_stats < schema_backup_before_cleanup.sql

# Restart application
systemctl restart myhokeystats
```

---

## Monitoring After Cleanup

### 1. Set up alerts for missing tables/functions

```sql
-- Create function to monitor deprecated table access
CREATE OR REPLACE FUNCTION log_deprecated_table_access()
RETURNS VOID AS $$
BEGIN
  RAISE EXCEPTION 'Attempt to access deprecated player_identity_map table';
END;
$$ LANGUAGE plpgsql;

-- Add trigger (if table still somehow exists as view or shadow)
-- This helps catch any remaining references in code
```

### 2. Application logging

Monitor logs for any "identity_map", "match_link", or "imported_player_record" references:

```bash
# Continuous monitoring
tail -f /var/log/myhokeystats/application.log | grep -i "identity_map\|match_link\|imported_player"

# If nothing appears for 24 hours post-cleanup, all good
```

### 3. Performance metrics

Track database query times:

```sql
-- Query plan changes (run periodically)
SELECT query, calls, mean_exec_time
FROM pg_stat_statements
WHERE query LIKE '%ayhl_player_career%' OR query LIKE '%season%'
ORDER BY mean_exec_time DESC
LIMIT 10;

-- Expected: Consistent ~5-50ms for typical season lookups
```

---

## Cleanup Report Template

After successful cleanup, document the results:

```
=== Cleanup Report: 002-integrate-player-data Deprecation ===

Date: [YYYY-MM-DD]
Environment: [staging/production]
Performed By: [name]

Pre-Cleanup State:
- player_identity_map records: [N]
- integration_match_link records: [N]
- integration_imported_player_record records: [N]
- Database size: [GB]

Cleanup Steps Executed:
[ ] backed up database
[ ] verified no code references
[ ] ran migrations (automatic or manual)
[ ] verified tables dropped
[ ] restarted application

Post-Cleanup Validation:
[ ] API endpoints responding (200 OK)
[ ] Player seasons lookup working
[ ] Parent access working
[ ] No error logs containing 'identity_map'
[ ] Query performance acceptable (<100ms p95)

Database Size After Cleanup: [GB]
Size Reduction: [% or MB]

Issues Encountered: [none]/[describe]
Resolution: [if applicable]

Approval Sign-Off:
- DBA: ________________
- Engineering Lead: ________________
```

---

## FAQ

**Q: Can I just DROP the tables immediately?**
A: No. Always backup first and run through the validation checklist. Unexpected data dependencies may exist.

**Q: What if the Flyway migrations don't run?**
A: They'll run on next deployment. If you need immediate cleanup, see "Manual Cleanup" section above.

**Q: How do I know if the cleanup was successful?**
A: Run the Post-Cleanup Validation section. No errors = success.

**Q: Can I keep the identity_map table for historical reference?**
A: Not recommended. It's a deprecated structure with stale data. Export to CSV first if needed for audit purposes.

**Q: What if an old API endpoint still references the identity_map?**
A: Update or remove that endpoint. New SeasonsController endpoint is the replacement.

---

## Support

For questions or issues related to this cleanup:

1. Check the APPLICATION_LOGS for "Flyway" or "migration" messages
2. Run the Post-Cleanup Validation section
3. Contact: [engineering-team@myhockeystats.com]

---

**Cleanup Process Complete** ✓
