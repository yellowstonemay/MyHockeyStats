# Data Model: Career Data Lookup & Seasons Display

**Phase**: Phase 1 - Design  
**Date**: 2026-03-31  

---

## Entities & Data Structures

### 1. Career Record (Abstract/Base)

Represents a single player career entry from one of the three sources (AYHL, THF, AHF).

**Fields** (existing in all three career tables):
- `id`: UUID (primary key)
- `source`: enum [AYHL, THF, AHF] (added as computed field in result set)
- `source_player_id`: varchar (original ID from source league)
- `player_name`: varchar (indexed, normalized for lookup)
- `season`: varchar (e.g., "2024-2025", "2024-25")
- `club`: varchar (club/association name)
- `team`: varchar (team name)
- `jersey_number`: integer (optional)
- `games_played`: integer
- `goals`: integer
- `assists`: integer
- `points`: integer (computed: goals + assists, for display clarity)
- `penalties`: integer
- `pim`: integer (penalty in minutes)
- `created_at`: timestamp
- `updated_at`: timestamp

**Storage**:
- Three separate tables: `ayhl_player_career`, `thf_player_career`, `ahf_player_career`
- identical schemas; no cross-source foreign keys

**Lookup Index**:
- Functional index on normalized name: `LOWER(TRIM(player_name))`
- Index should exist on each career table for performance

---

### 2. Season Lookup Request

Request structure for querying career records for a player.

**Process**:
1. Extract player's normalized name from PlayerProfile entity
2. Normalize name: LOWER(TRIM(name)) → remove common punctuation
3. Query all three career tables with normalized match
4. Return union of all matching records

**Normalized Name Function**:
```java
public static String normalizePlayerName(String fullName) {
    return fullName
        .toLowerCase()
        .trim()
        .replaceAll("['-.]", "")  // Remove common punctuation
        .replaceAll("\\s+", " "); // Normalize whitespace
}
```

**Example Normalization**:
- Input: "Jean-Pierre O'Brien"
- Normalized: "jeanpierre obrien"

---

### 3. Unified Season Career Response

**DTO**: `SeasonCareerRecordDto`

Represents a single career record row in the unified response (one record from one source for one season).

```java
@Data
public class SeasonCareerRecordDto {
    // Identity
    private String source; // "AYHL", "THF", "AHF"
    private String sourcePlayerId; // Original ID from source
    private String playerName; // Original player name from source
    
    // Season/Team Info
    private String season; // "2024-2025"
    private String club; // Club/association name
    private String team; // Team name
    private Integer jerseyNumber; // Optional
    
    // Statistics
    private Integer gamesPlayed;
    private Integer goals;
    private Integer assists;
    private Integer points; // Computed: goals + assists
    private Integer penalties;
    private Integer pim;
    
    // Metadata
    private LocalDateTime importedAt; // When data was imported
    private boolean isAmbiguousMembership; // true if multiple players found with same name
    private String ambiguityNote; // "Multiple players found. Verify by team/season." if needed
}
```

---

### 4. Seasons Lookup Response

**DTO**: `SeasonsResponseDto`

Wraps all career records for a player, including metadata about the lookup.

```java
@Data
public class SeasonsResponseDto {
    private String playerId; // UUID of queried player
    private String playerName; // From player profile
    private List<SeasonCareerRecordDto> records; // All matching records from all sources
    
    // Metadata
    private boolean hasAmbiguity; // true if multiple players found
    private String ambiguityNote; // Helpful message if ambiguity
    private Set<String> availableSources; // Which sources have data (e.g., ["AYHL", "THF"])
    private Set<String> emptySources; // Which sources have no data (e.g., ["AHF"])
    
    private LocalDateTime fetchedAt; // Timestamp of query
    private String cacheControl; // "private, max-age=300"
}
```

---

### 5. Error Response Structure

**DTO**: `ErrorResponseDto` (existing convention)

Standard error envelope for Seasons endpoint:

```java
@Data
public class ErrorResponseDto {
    private String code; // "CAREER_LOOKUP_FAILED", "UNAUTHORIZED", etc.
    private String message; // User-friendly description
    private String details; // Optional: technical details
    private LocalDateTime timestamp;
}
```

**Error Codes for Seasons Endpoint**:
- `UNAUTHORIZED`: User not authorized to view this player's data
- `PLAYER_NOT_FOUND`: Player ID doesn't exist
- `CAREER_LOOKUP_TIMEOUT`: Database query timeout
- `CAREER_LOOKUP_ERROR`: General database error
- `INVALID_PLAYER_NAME`: Player name is empty or null (should not occur in normal flow)

---

### 6. Authorization Model

**Decision**: Reuse existing `IntegrationAccessGuard` pattern with minimal extension.

**Access Rules**:
```
User CAN view Seasons for PlayerId IF:
  - User.id == PlayerId (player viewing own data), OR
  - AccountLink exists where: parent_account_id == User.id AND child_account_id == PlayerId (parent viewing linked child)
```

**Implementation**:
```java
public boolean canViewSeasons(String userId, String playerId) {
    // Player can always view own seasons
    if (userId.equals(playerId)) {
        return true;
    }
    
    // Check if parent is linked to player
    return accountLinkRepository.findByParentIdAndChildId(userId, playerId).isPresent();
}
```

---

### 7. Database Query Strategy

**Query Pattern**: UNION ALL across three sources with normalized name matching.

```sql
SELECT 
  'AYHL' as source,
  id,
  source_player_id,
  player_name,
  season,
  club,
  team,
  jersey_number,
  games_played,
  goals,
  assists,
  (goals + assists) as points,
  penalties,
  pim,
  updated_at as last_updated
FROM ayhl_player_career
WHERE LOWER(TRIM(player_name)) = LOWER(:normalizedName)

UNION ALL

SELECT 
  'THF' as source,
  id,
  source_player_id,
  player_name,
  season,
  club,
  team,
  jersey_number,
  games_played,
  goals,
  assists,
  (goals + assists) as points,
  penalties,
  pim,
  updated_at as last_updated
FROM thf_player_career
WHERE LOWER(TRIM(player_name)) = LOWER(:normalizedName)

UNION ALL

SELECT 
  'AHF' as source,
  id,
  source_player_id,
  player_name,
  season,
  club,
  team,
  jersey_number,
  games_played,
  goals,
  assists,
  (goals + assists) as points,
  penalties,
  pim,
  updated_at as last_updated
FROM ahf_player_career
WHERE LOWER(TRIM(player_name)) = LOWER(:normalizedName)

ORDER BY season DESC, source ASC;
```

**Performance**:
- Assumes functional indexes on normalized name exist (see Assumptions)
- Three index lookups + merge sort: O(n) where n = total matching records across sources
- Expected <100ms for typical player (0-20 matching seasons)

---

### 8. Caching & Cache Invalidation

**HTTP Cache Header** (set by backend):
- `Cache-Control: private, max-age=300`
- Browser caches response for 5 minutes
- User can force refresh with Ctrl+Shift+R

**Backend Cache** (optional, not required for MVP):
- Consider caching lookups in Redis if query load becomes high
- Cache key: `seasons:{playerId}:{normalizedName}` (tied to player profile)
- Invalidate cache when: player name changes in profile OR career data is imported/updated

**Event-Driven Invalidation** (future): When import process completes, publish event to invalidate related player caches.

---

### 9. Relationship Diagram

```
PlayerProfile (existing)
  ├─ id (UUID)
  ├─ user_id (FK -> User)
  └─ full_name (used for career lookup)
       │
       ├─ Lookup by normalized name
       │
       ├─────→ ayhl_player_career (all rows matching normalized name)
       │         └─ career records from AYHL
       │
       ├─────→ thf_player_career (all rows matching normalized name)
       │         └─ career records from THF
       │
       └─────→ ahf_player_career (all rows matching normalized name)
                 └─ career records from AHF

AccountLink (existing)
  └─ (parent_account_id, child_account_id)
     └─ Used by IntegrationAccessGuard to verify parent access to child's Seasons
```

---

### 10. SQL Schema: Career Tables Reference

**Career Table Schema** (assumed existing; no changes):

```sql
CREATE TABLE ayhl_player_career (
  id UUID PRIMARY KEY,
  source_player_id VARCHAR(255) UNIQUE,
  player_name VARCHAR(255) NOT NULL,
  season VARCHAR(20) NOT NULL,
  club VARCHAR(255),
  team VARCHAR(255),
  jersey_number INT,
  games_played INT,
  goals INT,
  assists INT,
  penalties INT,
  pim INT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for efficient normalized name lookup (RECOMMENDED)
CREATE INDEX idx_ayhl_player_career_normalized_name 
  ON ayhl_player_career (LOWER(TRIM(player_name)));

-- Repeat for thf_player_career and ahf_player_career
```

---

### 11. Deprecation: player_identity_map Table

**Current State**: Table exists (from old "integrated history" page).

**Action**: Mark for deprecation.
  1. Stop writing new rows (during implementation).
  2. Document that lookups now use direct career table query by name.
  3. Remove table + all code paths in Phase 2 (post-launch cleanup).

**No Data Migration**: Old rows don't need to be transferred; new flow is stateless runtime lookup.

---

## State Transitions

### Seasons Lookup Workflow

```
User opens Seasons tab
  ↓
Frontend calls: GET /api/players/{playerId}/seasons
  ↓
Backend extracts player's name from profile
  ↓
Backend normalizes name (lowercase, trim, remove punctuation)
  ↓
Backend queries all 3 career tables with normalized name
  ↓
Backend merges results, sorts by season DESC
  ↓
Backend detects ambiguity? (multiple players found with same name)
  ├─ YES: Add ambiguity note to response
  └─ NO: Proceed normally
  ↓
Backend sets HTTP cache headers (5 min TTL)
  ↓
Backend returns SeasonCareerRecordDto[] + metadata
  ↓
Frontend displays all records with source labels
  ↓
Browser caches response for 5 minutes
  ↓
User views season details or exports PDF
```

---

## Validation Rules

### Input Validation

- **playerId**: Must be valid UUID format; must belong to authenticated user or linked parent
- **Player Name**: Must be non-empty, non-null after trimming
- **Normalized Name**: Must have at least 2 characters after normalization (to avoid overly broad matches)

### Output Validation

- **Records**: List must not be null (empty list OK if no matches)
- **Stats**: All numeric fields (goals, assists, etc.) must be ≥ 0
- **Source Enum**: Must be one of ["AYHL", "THF", "AHF"]
- **Season Format**: Must match expected format (e.g., "2024-2025" or "2024-25")

---

## Summary of Entities Used

| Entity | Source | Purpose | Relationships |
|--------|--------|---------|---|
| PlayerProfile | Existing | Provides player name for lookup | 1:N to career records (runtime join) |
| ayhl_player_career | Existing | AYHL league data | 0:N matching records per lookup |
| thf_player_career | Existing | THF league data | 0:N matching records per lookup |
| ahf_player_career | Existing | AHF league data | 0:N matching records per lookup |
| AccountLink | Existing | Parent-child relationship | Used for auth check |
| User | Existing | Authenticated user | FK in auth check |
| (DEPRECATED) player_identity_map | Existing | Old persistent linking | To be removed |

