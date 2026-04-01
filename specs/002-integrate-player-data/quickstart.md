# Quickstart: Implementation Guide

**Phase**: Phase 1 - Design  
**Date**: 2026-03-31  
**Audience**: Backend and frontend developers  

---

## Overview

This guide provides a quick reference for implementing the Simplified Player Data Integration feature. Full details are in `plan.md`, `data-model.md`, and `contracts/api-contracts.md`.

**Feature Goal**: Replace old "Integrated History" page with new "Seasons" tab that directly queries career tables by player name.

**Key Change**: Stateless runtime lookup (no persistent linking) → simpler, faster, more transparent.

---

## Backend Implementation Checklist

### Phase 1: Core Endpoint (2-3 days)

#### Step 1: Create CareerLookupService

**File**: `backend/src/main/java/com/myhockeystats/service/integration/CareerLookupService.java`

```java
@Service
public class CareerLookupService {
    
    @Autowired
    private AyhlPlayerCareerRepository ayhlRepo;
    
    @Autowired
    private ThfPlayerCareerRepository thfRepo;
    
    @Autowired
    private AhfPlayerCareerRepository ahfRepo;
    
    /**
     * Normalize player name for lookup:
     * - lowercase
     * - trim whitespace
     * - remove punctuation (-, ', .)
     */
    public static String normalizePlayerName(String fullName) {
        return fullName
            .toLowerCase()
            .trim()
            .replaceAll("['-.]", "")
            .replaceAll("\\s+", " ");
    }
    
    /**
     * Lookup all career records from all sources by normalized player name.
     * Returns union of matching records sorted by season desc.
     */
    public SeasonsResponseDto lookupCareerRecordsByName(String playerName) {
        String normalized = normalizePlayerName(playerName);
        
        List<AyhlPlayerCareer> ayhlRecords = ayhlRepo.findByNormalizedName(normalized);
        List<ThfPlayerCareer> thfRecords = thfRepo.findByNormalizedName(normalized);
        List<AhfPlayerCareer> ahfRecords = ahfRepo.findByNormalizedName(normalized);
        
        // Convert to unified DTOs
        List<SeasonCareerRecordDto> allRecords = new ArrayList<>();
        allRecords.addAll(toSeasonDtos(ayhlRecords, "AYHL"));
        allRecords.addAll(toSeasonDtos(thfRecords, "THF"));
        allRecords.addAll(toSeasonDtos(ahfRecords, "AHF"));
        
        // Sort by season desc, then source asc
        allRecords.sort(Comparator
            .comparing(SeasonCareerRecordDto::getSeason).reversed()
            .thenComparing(SeasonCareerRecordDto::getSource));
        
        // Detect ambiguity (multiple distinct players with same name)
        Set<String> uniqueSourceIds = allRecords.stream()
            .map(r -> r.getSource() + ":" + r.getSourcePlayerId())
            .collect(Collectors.toSet());
        boolean hasAmbiguity = uniqueSourceIds.size() > 1;
        
        // Build response
        Set<String> availableSources = allRecords.stream()
            .map(SeasonCareerRecordDto::getSource)
            .collect(Collectors.toSet());
        Set<String> emptySources = Stream.of("AYHL", "THF", "AHF")
            .filter(s -> !availableSources.contains(s))
            .collect(Collectors.toSet());
        
        return SeasonsResponseDto.builder()
            .records(allRecords)
            .hasAmbiguity(hasAmbiguity)
            .ambiguityNote(hasAmbiguity ? "Multiple players found with this name. Verify by team/season." : null)
            .availableSources(availableSources)
            .emptySources(emptySources)
            .fetchedAt(LocalDateTime.now(ZoneOffset.UTC))
            .cacheControl("private, max-age=300")
            .build();
    }
    
    private List<SeasonCareerRecordDto> toSeasonDtos(List<?> records, String source) {
        // Convert entity records to DTOs with source label
        // Implementation varies by source type
        // Pattern: entity.toDto() + set source field
        return records.stream()
            .map(r -> mapToDto(r, source))
            .collect(Collectors.toList());
    }
}
```

#### Step 2: Add Repository Interfaces

For each career table, add a repository method for normalized name lookup:

**File**: `backend/src/main/java/com/myhockeystats/repository/AyhlPlayerCareerRepository.java`

```java
@Repository
public interface AyhlPlayerCareerRepository extends JpaRepository<AyhlPlayerCareer, UUID> {
    
    /**
     * Find all records where LOWER(TRIM(player_name)) = normalized_name.
     * Uses functional index for performance.
     */
    @Query("SELECT r FROM AyhlPlayerCareer r " +
           "WHERE LOWER(TRIM(r.playerName)) = :normalizedName " +
           "ORDER BY r.season DESC")
    List<AyhlPlayerCareer> findByNormalizedName(@Param("normalizedName") String normalizedName);
}
```

Repeat `findByNormalizedName` for `ThfPlayerCareerRepository` and `AhfPlayerCareerRepository`.

#### Step 3: Create SeasonsController Method

**File**: `backend/src/main/java/com/myhockeystats/api/SeasonsController.java`

```java
@RestController
@RequestMapping("/api/players/{playerId}/seasons")
public class SeasonsController {
    
    @Autowired
    private PlayerProfileService playerProfileService;
    
    @Autowired
    private CareerLookupService careerLookupService;
    
    @Autowired
    private IntegrationAccessGuard accessGuard;
    
    /**
     * GET /api/players/{playerId}/seasons
     * 
     * Returns all career records for a player from all sources
     * matched by normalized player name.
     * 
     * Authorization: Authenticated user must be the player or a linked parent.
     * Cache: 5 minutes (browser cache).
     */
    @GetMapping
    public ResponseEntity<SeasonsResponseDto> getPlayerSeasons(
            @PathVariable UUID playerId,
            HttpServletRequest request) {
        
        // Check authorization
        String userId = getCurrentUserId(request);
        if (!accessGuard.canViewSeasons(userId, playerId)) {
            return ResponseEntity.status(401)
                .body(new ErrorResponseDto("UNAUTHORIZED", "You do not have permission..."));
        }
        
        // Get player profile
        PlayerProfile player = playerProfileService.getPlayerProfile(playerId)
            .orElse(null);
        if (player == null) {
            return ResponseEntity.status(404)
                .body(new ErrorResponseDto("PLAYER_NOT_FOUND", "Player not found."));
        }
        
        // Lookup career records
        try {
            SeasonsResponseDto response = careerLookupService
                .lookupCareerRecordsByName(player.getFullName());
            
            response.setPlayerId(playerId.toString());
            response.setPlayerName(player.getFullName());
            
            return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePrivate())
                .body(response);
        } catch (DatabaseTimeoutException e) {
            return ResponseEntity.status(504)
                .body(new ErrorResponseDto("CAREER_LOOKUP_TIMEOUT", 
                    "Career data lookup timed out. Please try again."));
        } catch (DatabaseException e) {
            return ResponseEntity.status(500)
                .body(new ErrorResponseDto("CAREER_LOOKUP_ERROR", 
                    "Failed to retrieve career data. Please try again."));
        }
    }
    
    private String getCurrentUserId(HttpServletRequest request) {
        // Extract user ID from JWT token or session
        // Use existing JwtUtil or SecurityContext
        return SecurityContextHolder.getContext()
            .getAuthentication()
            .getName();
    }
}
```

#### Step 4: Add DTOs

Create `SeasonCareerRecordDto` and `SeasonsResponseDto` in `api/dto/integration/`:

```java
@Data
@Builder
public class SeasonCareerRecordDto {
    private String source; // AYHL, THF, AHF
    private String sourcePlayerId;
    private String playerName;
    private String season;
    private String club;
    private String team;
    private Integer jerseyNumber;
    private Integer gamesPlayed;
    private Integer goals;
    private Integer assists;
    private Integer points;
    private Integer penalties;
    private Integer pim;
    private LocalDateTime importedAt;
    private boolean isAmbiguousMembership;
    private String ambiguityNote;
}

@Data
@Builder
public class SeasonsResponseDto {
    private String playerId;
    private String playerName;
    private List<SeasonCareerRecordDto> records;
    private boolean hasAmbiguity;
    private String ambiguityNote;
    private Set<String> availableSources;
    private Set<String> emptySources;
    private LocalDateTime fetchedAt;
    private String cacheControl;
}
```

#### Step 5: Extend IntegrationAccessGuard

**File**: `backend/src/main/java/com/myhockeystats/security/IntegrationAccessGuard.java`

Add method:

```java
public boolean canViewSeasons(String userId, UUID playerId) {
    // Player can always view own seasons
    if (userId.equals(playerId.toString())) {
        return true;
    }
    
    // Check if parent is linked to player
    Optional<AccountLink> link = accountLinkRepository
        .findByParentUserIdAndChildPlayerId(UUID.fromString(userId), playerId);
    return link.isPresent();
}
```

### Phase 2: Database Indexes (1 day)

#### Add Indexes to Career Tables

```sql
-- AYHL
CREATE INDEX idx_ayhl_player_career_normalized_name 
  ON ayhl_player_career (LOWER(TRIM(player_name)));

-- THF
CREATE INDEX idx_thf_player_career_normalized_name 
  ON thf_player_career (LOWER(TRIM(player_name)));

-- AHF
CREATE INDEX idx_ahf_player_career_normalized_name 
  ON ahf_player_career (LOWER(TRIM(player_name)));
```

Add to migration: `backend/src/main/resources/db/migration/V[X]__add_career_lookupindexes.sql`

### Phase 3: Cleanup (2-3 days)

#### Remove Old Endpoints

1. Delete `IntegrationPlayerController.getMatches()` method
2. Delete `IntegrationPlayerController.confirmMatch()` method
3. Remove route `/api/integrations/me/match-status`
4. Remove route `/api/integrations/me/matches/confirm`

#### Remove Old Services

1. Delete or deprecate `MatchCandidateService`
2. Delete or deprecate `IdentityMapService` (if it managed persistent linking)

#### Deprecate Database Table

1. **Mark for removal**: Do NOT delete yet (data safety)
2. Add comment: `-- DEPRECATED: To be removed in v2.0. Use CareerLookupService instead.`
3. Update documentation to note deprecation

```sql
-- Migration: V[X]__deprecate_player_identity_map.sql
-- DEPRECATED: player_identity_map table is no longer used.
-- Lookups now use direct career table query by normalized name (stateless).
-- This table will be dropped in a future version.
-- No more writes to this table after [DATE].

-- ALTER TABLE player_identity_map ADD COLUMN deprecated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
-- (do not execute; just mark as deprecated for now)
```

---

## Frontend Implementation Checklist

### Phase 1: Seasons Tab Component (3-4 days)

#### Step 1: Create SeasonsTab Component

**File**: `frontend/web/src/components/PlayerProfile/SeasonsTab.jsx`

```jsx
import { useState, useEffect } from 'react';
import { fetchPlayerSeasons } from '@/lib/api';

export function SeasonsTab({ playerId, playerName }) {
  const [seasons, setSeasons] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [retrying, setRetrying] = useState(false);

  useEffect(() => {
    loadSeasons();
  }, [playerId]);

  const loadSeasons = async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await fetchPlayerSeasons(playerId);
      setSeasons(response.records);
      
      // If ambiguous, show note
      if (response.hasAmbiguity) {
        setError({
          type: 'ambiguity',
          message: response.ambiguityNote
        });
      }
    } catch (err) {
      setError({
        type: err.code || 'error',
        message: err.message || 'Failed to load seasons'
      });
    } finally {
      setLoading(false);
      setRetrying(false);
    }
  };

  const handleRetry = () => {
    setRetrying(true);
    loadSeasons();
  };

  if (loading && !retrying) {
    return <div className="text-center py-8">Loading seasons...</div>;
  }

  if (error && error.type !== 'ambiguity') {
    return (
      <div className="bg-red-50 border border-red-200 rounded p-4">
        <p className="text-red-800">{error.message}</p>
        <button 
          onClick={handleRetry}
          className="mt-2 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700"
        >
          Retry
        </button>
      </div>
    );
  }

  if (seasons.length === 0) {
    return (
      <div className="text-center py-8 text-gray-500">
        <p>No historical records found.</p>
        <p className="text-sm">Career data may not be imported yet.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error?.type === 'ambiguity' && (
        <div className="bg-yellow-50 border border-yellow-200 rounded p-3">
          <p className="text-yellow-800 text-sm">{error.message}</p>
        </div>
      )}
      
      {seasons.map((record, idx) => (
        <SeasonCard key={idx} record={record} />
      ))}
    </div>
  );
}

function SeasonCard({ record }) {
  return (
    <div className="border rounded p-4 bg-gray-50">
      <div className="flex justify-between items-start mb-3">
        <div>
          <h3 className="font-semibold">{record.season}</h3>
          <p className="text-sm text-gray-600">{record.team}, {record.club}</p>
        </div>
        <span className="px-2 py-1 bg-blue-100 text-blue-800 rounded text-xs font-semibold">
          {record.source}
        </span>
      </div>
      
      <div className="grid grid-cols-2 gap-4 text-sm">
        <div>
          <p className="text-gray-600">Jersey</p>
          <p className="font-semibold">{record.jerseyNumber || 'N/A'}</p>
        </div>
        <div>
          <p className="text-gray-600">Games Played</p>
          <p className="font-semibold">{record.gamesPlayed}</p>
        </div>
        <div>
          <p className="text-gray-600">Goals</p>
          <p className="font-semibold">{record.goals}</p>
        </div>
        <div>
          <p className="text-gray-600">Assists</p>
          <p className="font-semibold">{record.assists}</p>
        </div>
        <div>
          <p className="text-gray-600">Points</p>
          <p className="font-semibold">{record.points}</p>
        </div>
        <div>
          <p className="text-gray-600">PIM</p>
          <p className="font-semibold">{record.pim}</p>
        </div>
      </div>
    </div>
  );
}
```

#### Step 2: Update API Client

**File**: `frontend/web/src/lib/api.js`

Add function:

```javascript
export async function fetchPlayerSeasons(playerId) {
  const response = await fetch(`/api/players/${playerId}/seasons`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${getAccessToken()}`,
      'Content-Type': 'application/json',
    },
  });

  if (response.status === 401) {
    throw new Error('Unauthorized');
  }
  if (response.status === 404) {
    throw new Error('Player not found');
  }
  if (response.status === 504) {
    const err = new Error('Lookup timeout. Please try again.');
    err.code = 'CAREER_LOOKUP_TIMEOUT';
    throw err;
  }
  if (!response.ok) {
    const err = await response.json();
    throw new Error(err.message || 'Failed to load seasons');
  }

  return response.json();
}
```

#### Step 3: Integrate Seasons Tab into Player Profile Page

**File**: `frontend/web/src/pages/PlayerProfile.jsx`

Replace old "Integrated History" tab section with:

```jsx
import { SeasonsTab } from '@/components/PlayerProfile/SeasonsTab';

export function PlayerProfile() {
  // ... existing code ...

  return (
    <div className="player-profile">
      <Tabs>
        <Tab label="Profile" value="profile">
          {/* existing profile tab */}
        </Tab>
        
        <Tab label="Seasons" value="seasons">
          <SeasonsTab playerId={player.id} playerName={player.fullName} />
        </Tab>
        
        {/* remove old Integrated History tab */}
      </Tabs>
    </div>
  );
}
```

### Phase 2: Remove Old Components (1-2 days)

#### Remove Files

1. Delete `frontend/web/src/pages/IntegratedHistory.jsx` (or entire page)
2. Delete `frontend/web/src/components/MatchConfirmation/` directory
3. Delete `frontend/web/src/components/MatchSelector/` directory

#### Update Router

**File**: `frontend/web/src/App.jsx`

Remove route:
```javascript
// Remove or comment out:
// { path: '/integrated-history', component: IntegratedHistory }
```

#### Update Navigation

Remove link from main menu/sidebar to old "Integrated History" page.

### Phase 3: Testing (2-3 days)

#### Manual Testing Checklist

- [ ] Player sees all career records in Seasons tab for own profile
- [ ] Parent sees same career records when viewing linked child's profile
- [ ] Ambiguity note displays when multiple players share same name
- [ ] Error message + retry shows for database timeout
- [ ] "No records found" message displays for player with no career data
- [ ] Source label (AYHL/THF/AHF) visible for each season row
- [ ] All stats display correctly (GP, G, A, Pts, PIM)
- [ ] Browser caching works (refresh within 5 min shows cached data)

#### Unit Test Stubs

**Backend**: `backend/src/test/java/.../CareerLookupServiceTest.java`

```java
@Test
void testNormalizePlayerName() {
  assertEquals("jeanpierre obrien", 
    CareerLookupService.normalizePlayerName("Jean-Pierre O'Brien"));
}

@Test
void testLookupCareerRecords_Success() {
  // Mock repos, verify query called with normalized name
}

@Test
void testLookupCareerRecords_Ambiguity() {
  // Mock multiple records, verify hasAmbiguity = true
}

@Test
void testLookupCareerRecords_Empty() {
  // Mock empty results, verify records[] is empty
}
```

**Frontend**: `frontend/web/src/components/__tests__/SeasonsTab.test.jsx`

```javascript
test('displays career records', () => {
  // Mock fetchPlayerSeasons, render SeasonsTab
  // Verify records render with source labels
});

test('displays error and retry button on failure', () => {
  // Mock error response
  // Verify error message + retry button display
});
```

---

## Timeline & Effort Estimate

| Phase | Task | Duration | Notes |
|-------|------|----------|-------|
| 1a | CareerLookupService + DTOs | 2-3 days | Core logic |
| 1b | SeasonsController endpoint | 1 day | REST integration |
| 1c | Repository methods + indexes | 1 day | Database tuning |
| 2 | SeasonsTab component | 3-4 days | React component + API integration |
| 3 | Remove old code + routes | 1-2 days | Cleanup |
| 4 | Testing (unit + manual) | 2-3 days | Validation |
| **Total** | | **11-14 days** | ~2 weeks |

---

## Common Pitfalls

### 1. Name Normalization Inconsistency

**Problem**: Normalization applied differently in some places (e.g., database vs. Java).

**Solution**: Use single `CareerLookupService.normalizePlayerName()` method centrally.

### 2. Missing Indexes

**Problem**: Career table queries are slow without functional index on normalized name.

**Solution**: Add indexes BEFORE deployment; verify performance in staging.

### 3. Authorization Check Missed

**Problem**: Frontend calls endpoint without auth check; parent can access unlinked player data.

**Solution**: Always verify `userId == playerId OR isParentLinked` in backend (never trust frontend).

### 4. Cache Headers Not Set

**Problem**: Browser doesn't cache response; backend gets hammered.

**Solution**: Always set `Cache-Control: private, max-age=300` in SeasonsController.

### 5. Ambiguity Note Not Displayed

**Problem**: User sees multiple "John Smith" rows but no indication that name is ambiguous.

**Solution**: Frontend checks `response.hasAmbiguity` and displays warning banner.

---

## Rollback Plan

If issues arise during deployment:

1. **Quick rollback**: Deploy version without Seasons tab
2. **Keep old endpoints temporarily**: Keep `IntegrationPlayerController` methods live until v2.0
3. **Monitor** for errors: Check backend logs for unexpected exceptions
4. **Gradual rollout**: Deploy to 10% of users first, then 50%, then 100%

---

## Success Metrics

After launch, verify:
- ✅ 95%+ of requests complete in < 1 second
- ✅ 0 errors for players with <50 matching records
- ✅ 0 auth bypass issues (parent access check working)
- ✅ 5-min cache hit rate > 70%
- ✅ Player feedback: "I can see my entire career history now"

