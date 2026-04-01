# Research & Design Decisions: Simplified Player Data Integration

**Phase**: Phase 0 - Research (COMPLETE)  
**Date**: 2026-03-31  
**Created by**: Implementation Planner  

---

## Resolved Clarifications from Feature Spec

All core clarifications were explicitly resolved in the feature spec session 2026-03-31. This document summarizes the decisions and their rationale.

### Decision 1: Name-Based Lookup (vs. Name + Birthdate or ID)

**Decision**: Match career records by **name only** using exact normalized match:
- Lowercase all characters
- Trim leading/trailing whitespace
- Remove common punctuation (hyphens, apostrophes, periods)

**Rationale**: 
- Many data sources (THF, AYHL, AHF) do not consistently provide birthdate.
- Name-based lookup is reliable enough for the youth hockey domain where duplicate names are rare.
- Player can disambiguate by reviewing team/season info when collisions occur.

**Alternatives Considered**:
- **Fuzzy matching (Levenshtein, Soundex)**: Rejected because increases false positives and adds complexity without clear value for controlled domain.
- **Name + Birthdate**: Rejected because birthdates are frequently missing in source data.
- **External identity provider (OAuth, federation)**: Rejected because out of scope and teams don't use centralized identity; name matching is sufficient MVP.

**Source Tables & Fields**:
- `ayhl_player_career`: (name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim)
- `thf_player_career`: (name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim)
- `ahf_player_career`: (name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim)

---

### Decision 2: Handling Ambiguous Matches

**Decision**: Display all matching records with prominent ambiguity note:
- Show every record that matches the normalized player name from each source.
- Include source attribution and full stats for each record.
- Add note: "Multiple players found with this name. Verify by team/season."

**Rationale**:
- Transparency over filtering: let the user see all possibilities.
- Youth hockey has low duplicate name overlap; if collisions occur, player can verify by team/season.
- Avoids building complex conflict resolution logic.

**Error Handling**:
- Database timeout/error: Show friendly message with retry button.
- No records found: Show "No historical records found" with explanation that data may not be imported yet.

---

### Decision 3: Parent Access Control

**Decision**: Use existing account link system; no new linking mechanism required.

**Rationale**:
- Platform already has account linking (parent <-> player) via existing `account_link` table/auth.
- Seasons endpoint authorization: return data if authenticated user is the player OR a linked parent of the player.
- Reuses existing `IntegrationAccessGuard` security component.

**Implementation Detail**:
- Backend checks: `userId == playerId OR isParentLinkedToPlayer(userId, playerId)`
- Frontend: parent navigates to linked child's profile, sees Seasons tab with same data as child sees.

---

### Decision 4: Caching Strategy

**Decision**: Use HTTP cache headers with 5-minute TTL (Cache-Control: private, max-age=300).

**Rationale**:
- Career data doesn't change minute-to-minute.
- 5 minutes is acceptable for player-facing read-only data.
- Reduces backend load during session reuse; browser stores cached response.
- Explicit TTL avoids staleness issues from long-term caching.

**Implementation Detail**:
- Backend sets `Cache-Control: private, max-age=300` on Seasons endpoint response.
- Frontend does not override; lets HTTP caching handle cache invalidation.
- User can force refresh with browser F5 or Ctrl+Shift+R if needed.

---

### Decision 5: Multi-Source Duplicate Display

**Decision**: Display as separate rows per source, even for same season/team combination if cached records differ.

**Rationale**:
- Transparency: all data is visible.
- Some sources may have different stats for same player/season due to timing of data capture or data entry errors.
- Avoids requiring logic to merge or pick a "canonical" record.
- User can compare across sources visually.

**Example**: Player "John Smith" in AYHL 2024-2025 season (5 GP, 2 G, 1 A) and THF 2024-2025 season (6 GP, 3 G, 1 A) → shows two rows with full stats and source labels.

---

## Best Practices Research

### Spring Boot REST API Design

**Pattern**: RESTful endpoint following Spring Boot conventions from existing codebase.

**Endpoint**: `GET /api/players/{playerId}/seasons` (or `/api/seasons/me` if user-relative)

**Considerations**:
- Existing codebase uses `SeasonController` and `PlayerProfileController`.
- Use existing `IntegrationAccessGuard` for authorization.
- Return normalized response envelope matching existing error pattern.

**HTTP Caching**: Use Spring's `CacheControl` builder:
```java
return ResponseEntity.ok()
    .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePrivate())
    .body(seasonData);
```

### Query Optimization for Career Lookup

**SQL Pattern**: Single query with normalized name matching across three tables via UNION ALL:

```sql
(SELECT 'AYHL' as source, * FROM ayhl_player_career WHERE LOWER(TRIM(name)) = :normalized_name ORDER BY season DESC)
UNION ALL
(SELECT 'THF' as source, * FROM thf_player_career WHERE LOWER(TRIM(name)) = :normalized_name ORDER BY season DESC)
UNION ALL
(SELECT 'AHF' as source, * FROM ahf_player_career WHERE LOWER(TRIM(name)) = :normalized_name ORDER BY season DESC)
ORDER BY season DESC, source ASC
```

**Index Recommendation**: Add functional index on normalized name if not present:
```sql
CREATE INDEX idx_career_normalized_name 
  ON ayhl_player_career (LOWER(TRIM(name)));
-- Repeat for THF and AHF tables
```

### Authorization Pattern

**Existing Guard**: `IntegrationAccessGuard` already implements:
- Check if user is player
- Check if user is parent linked to player

**Reuse Strategy**: Inject guard into new `SeasonsService` or endpoint.

### Frontend Component Reuse

**Pattern**: Existing codebase likely has season/game display components from 001-youth-homepage spec.

**Approach**: 
- Reuse existing `SeasonCard` / `GameRow` components.
- Pass unified response (all sources merged at endpoint level).
- Add source attribution label to each row.

---

## Technology Decisions Summary

| Aspect | Decision | Alternative | Why |
|--------|----------|-------------|-----|
| **Lookup Strategy** | Name only, exact normalized match | Fuzzy match, name+birthdate | Name-only covers MVP; reliable in youth hockey domain |
| **Ambiguity Handling** | Display all records with note | Filter/merge, fail with error | Transparency + simplicity |
| **Parent Access** | Existing account link + auth check | New linking UI/table | Reuse, no new complexity |
| **Caching** | HTTP 5-min TTL | No caching / aggressive caching | Balance freshness vs. perf |
| **Multi-Source Display** | Separate rows per source | Merge/conflict resolution | Transparency, no logic needed |
| **Query Pattern** | UNION ALL across career tables | Separate calls / JOIN with view | Single round-trip, simple |
| **Access Control** | Extend IntegrationAccessGuard | New security check | Consistent with codebase |

---

## Remaining Unknowns: NONE

All technical decisions have been resolved via clarifications in the spec. No further research is required before Phase 1 design.

---

## Design Decisions Affecting Architecture

### Backend Changes Required

1. **New Endpoint**: `GET /api/players/{playerId}/seasons` → returns all career records from 3 sources
2. **New Service**: `CareerLookupService` → queries career tables with normalized name
3. **Authorization**: Extend `IntegrationAccessGuard` or new `SeasonsAccessGuard`
4. **DTO**: New `SeasonCareerRecordDto` to represent unified career row
5. **Cleanup**: Remove `IntegrationPlayerController.getMatches()`, `IntegrationPlayerController.confirmMatch()` and related services

### Frontend Changes Required

1. **New Seasons Tab**: Replace old "Integrated History" page with inline tab in player profile
2. **Data Fetch**: Call new `GET /api/players/{playerId}/seasons` endpoint
3. **Display**: Render all records as list of season rows with source attribution and stats
4. **Ambiguity UI**: Show note if multiple players found with same name
5. **Error UI**: Show error + retry for failed lookups
6. **Cleanup**: Remove integrated-history page, confirmation flow, old endpoint calls

### Database Changes Required

1. **Deprecate**: `player_identity_map` table (mark for removal after migration)
2. **Indexes**: Add normalized name indexes to career tables (if not present)
3. **No Schema Changes**: Career table columns remain unchanged

---

## Assumptions Validated

✅ Career tables (ayhl_player_career, thf_player_career, ahf_player_career) exist and are regularly updated.  
✅ Player profile includes name field used for lookup.  
✅ AccountLink rows already exist for parent-child relationships.  
✅ HTTP caching is acceptable for 5-minute data freshness.  
✅ No persistent linking needed; runtime lookup is sufficient.  

---

## Next Steps: Phase 1 Design

Phase 1 will produce:
1. **data-model.md** - Career lookup entities and data structures
2. **contracts/api-contracts.md** - New Seasons endpoint contract
3. **quickstart.md** - Implementation quick-start for backend + frontend
4. **plan.md** - Full implementation plan with phases, timeline, testing strategy
