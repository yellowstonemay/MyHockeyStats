# API Contracts: Seasons Career Lookup

**Phase**: Phase 1 - Design  
**Date**: 2026-03-31  
**Base URL**: `/api`

---

## NEW: GET /api/players/{playerId}/seasons

Returns all career records for a player from AYHL, THF, and AHF sources, matched by normalized player name.

### Request

**Method**: GET  
**Path**: `/api/players/{playerId}/seasons`  
**Authentication**: Required (Bearer token or session cookie)  
**Authorization**: Authenticated user must be the player OR a parent linked to the player

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `playerId` | UUID | Yes | Player profile ID |

### Query Parameters

None (MVP: no filtering by season/source; all records returned)

### Request Headers

```
Authorization: Bearer {accessToken}
Content-Type: application/json (implied)
Accept: application/json
```

### Request Body

None (GET request)

### Response (200 OK)

**Content-Type**: `application/json`  
**Cache-Control**: `private, max-age=300`

**Response Body**:

```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "playerName": "John Smith",
  "records": [
    {
      "source": "AYHL",
      "sourcePlayerId": "ayhl-12345",
      "playerName": "John Smith",
      "season": "2024-2025",
      "club": "Ontario Minor Hockey",
      "team": "Toronto U18",
      "jerseyNumber": 7,
      "gamesPlayed": 32,
      "goals": 12,
      "assists": 8,
      "points": 20,
      "penalties": 4,
      "pim": 8,
      "importedAt": "2026-03-28T10:15:00Z",
      "isAmbiguousMembership": false,
      "ambiguityNote": null
    },
    {
      "source": "THF",
      "sourcePlayerId": "thf-67890",
      "playerName": "John Smith",
      "season": "2024-2025",
      "club": "Thunder Bay Minor Hockey",
      "team": "Thunder Bay U18",
      "jerseyNumber": 12,
      "gamesPlayed": 28,
      "goals": 10,
      "assists": 6,
      "points": 16,
      "penalties": 2,
      "pim": 4,
      "importedAt": "2026-03-27T14:32:00Z",
      "isAmbiguousMembership": false,
      "ambiguityNote": null
    },
    {
      "source": "AHF",
      "sourcePlayerId": "ahf-54321",
      "playerName": "John Smith",
      "season": "2023-2024",
      "club": "Alberta Youth Hockey",
      "team": "Calgary U17",
      "jerseyNumber": 9,
      "gamesPlayed": 30,
      "goals": 14,
      "assists": 9,
      "points": 23,
      "penalties": 3,
      "pim": 6,
      "importedAt": "2026-03-25T09:00:00Z",
      "isAmbiguousMembership": false,
      "ambiguityNote": null
    }
  ],
  "hasAmbiguity": false,
  "ambiguityNote": null,
  "availableSources": ["AYHL", "THF", "AHF"],
  "emptySources": [],
  "fetchedAt": "2026-03-31T12:00:00Z",
  "cacheControl": "private, max-age=300"
}
```

**Schema Details**:
- `playerId`: UUID of the queried player
- `playerName`: Current player profile name (used for lookup)
- `records`: Array of SeasonCareerRecordDto (sorted by season DESC)
- `hasAmbiguity`: Boolean; true if multiple players found with same normalized name
- `ambiguityNote`: String; helpful note if ambiguity detected (e.g., "Multiple players found with this name. Verify by team/season.")
- `availableSources`: Set of source names that have records for this player
- `emptySources`: Set of source names that have NO records for this player
- `fetchedAt`: ISO-8601 timestamp when query executed
- `cacheControl`: HTTP cache control header value (for client-side reference)

### Response (200 OK - No Records Found)

```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "playerName": "Nonexistent Player",
  "records": [],
  "hasAmbiguity": false,
  "ambiguityNote": null,
  "availableSources": [],
  "emptySources": ["AYHL", "THF", "AHF"],
  "fetchedAt": "2026-03-31T12:00:00Z",
  "cacheControl": "private, max-age=300"
}
```

### Response (200 OK - Ambiguous Match)

When multiple distinct players share the same normalized name, all records are returned with ambiguity flag:

```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "playerName": "John Smith",
  "records": [
    {
      "source": "AYHL",
      "sourcePlayerId": "ayhl-111",
      "playerName": "John Smith",
      "season": "2025",
      "club": "AYHL Club 1",
      "team": "Team A",
      "jerseyNumber": 10,
      "gamesPlayed": 20,
      "goals": 5,
      "assists": 3,
      "points": 8,
      "penalties": 1,
      "pim": 2,
      "importedAt": "2026-03-28T10:00:00Z",
      "isAmbiguousMembership": true,
      "ambiguityNote": "Multiple players found with this name. Verify by team/season."
    },
    {
      "source": "AYHL",
      "sourcePlayerId": "ayhl-222",
      "playerName": "John Smith",
      "season": "2024",
      "club": "AYHL Club 2",
      "team": "Team B",
      "jerseyNumber": 15,
      "gamesPlayed": 25,
      "goals": 10,
      "assists": 7,
      "points": 17,
      "penalties": 2,
      "pim": 4,
      "importedAt": "2026-03-28T10:00:00Z",
      "isAmbiguousMembership": true,
      "ambiguityNote": "Multiple players found with this name. Verify by team/season."
    }
  ],
  "hasAmbiguity": true,
  "ambiguityNote": "Multiple players found with this name. Verify by team/season.",
  "availableSources": ["AYHL"],
  "emptySources": ["THF", "AHF"],
  "fetchedAt": "2026-03-31T12:00:00Z",
  "cacheControl": "private, max-age=300"
}
```

---

### Error Response (401 Unauthorized)

```json
{
  "code": "UNAUTHORIZED",
  "message": "You do not have permission to view this player's seasons.",
  "details": "Authenticated user is neither the player nor a linked parent.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

**Status Code**: 401  
**Reason**: Authenticated user is not the player and is not a parent linked to the player.

---

### Error Response (404 Player Not Found)

```json
{
  "code": "PLAYER_NOT_FOUND",
  "message": "Player profile not found.",
  "details": "Player ID does not exist.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

**Status Code**: 404  
**Reason**: playerId does not correspond to an existing PlayerProfile.

---

### Error Response (500 Career Lookup Error)

```json
{
  "code": "CAREER_LOOKUP_ERROR",
  "message": "Failed to retrieve career data. Please try again.",
  "details": "Database connection error when querying career tables.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

**Status Code**: 500  
**Reason**: Database query failed (connection error, unexpected schema issue, etc.)

---

### Error Response (504 Database Timeout)

```json
{
  "code": "CAREER_LOOKUP_TIMEOUT",
  "message": "Career data lookup timed out. Please try again.",
  "details": "Query exceeded 30-second timeout.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

**Status Code**: 504 (Gateway Timeout)  
**Reason**: Database query did not complete within acceptable time.  
**Frontend Action**: Display retry button; allow user to refresh without page reload.

---

### Error Response (400 Bad Request - Invalid Player ID)

```json
{
  "code": "INVALID_REQUEST",
  "message": "Invalid player ID format.",
  "details": "Player ID must be a valid UUID.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

**Status Code**: 400  
**Reason**: playerId is not a valid UUID format.

---

## DEPRECATED ENDPOINTS (To Be Removed)

These endpoints were part of the old "Integrated History" flow. They will be removed during Phase 2 cleanup:

### ~~GET /api/integrations/me/match-status~~

(Removed during redesign; status check no longer needed as lookup is stateless)

### ~~POST /api/integrations/me/matches/confirm~~

(Removed during redesign; no confirmation step, all records displayed by default)

---

## HTTP Caching Behavior

### Browser Caching

**Header Set by Backend**:
```
Cache-Control: private, max-age=300
```

**Browser Behavior**:
- Cache 200 OK response for 5 minutes (300 seconds)
- Serve cached response to same client for subsequent requests within 5 minutes
- Do NOT share cache across users (private flag)
- User can force refresh with Ctrl+Shift+R or clear browser cache

**Example**:
1. User loads Seasons tab at 12:00 PM → query executed, result cached
2. User navigates away and back within 5 min (12:03 PM) → cached result served (no query)
3. User navigates away and back after 5 min (12:06 PM) → new query executed

---

## Request/Response Examples in cURL

### Example 1: Successful Lookup with Multiple Records

```bash
curl -X GET "http://localhost:8080/api/players/550e8400-e29b-41d4-a716-446655440000/seasons" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/json"
```

**Response**:
```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "playerName": "John Smith",
  "records": [ /* 3 records from AYHL, THF, AHF */ ],
  "hasAmbiguity": false,
  "availableSources": ["AYHL", "THF", "AHF"],
  "emptySources": []
}
```

### Example 2: Ambiguous Match (Multiple Players with Same Name)

```bash
curl -X GET "http://localhost:8080/api/players/550e8400-e29b-41d4-a716-446655440001/seasons" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/json"
```

**Response**:
```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440001",
  "playerName": "John Smith",
  "records": [ /* 5 records: multiple "John Smith" entries from AYHL */ ],
  "hasAmbiguity": true,
  "ambiguityNote": "Multiple players found with this name. Verify by team/season.",
  "availableSources": ["AYHL"],
  "emptySources": ["THF", "AHF"]
}
```

### Example 3: No Records Found

```bash
curl -X GET "http://localhost:8080/api/players/550e8400-e29b-41d4-a716-446655440002/seasons" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/json"
```

**Response**:
```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440002",
  "playerName": "Unknown Player",
  "records": [],
  "hasAmbiguity": false,
  "availableSources": [],
  "emptySources": ["AYHL", "THF", "AHF"]
}
```

### Example 4: Unauthorized Access

```bash
curl -X GET "http://localhost:8080/api/players/550e8400-e29b-41d4-a716-446655440003/seasons" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/json"
```

**Response** (401 Unauthorized):
```json
{
  "code": "UNAUTHORIZED",
  "message": "You do not have permission to view this player's seasons.",
  "details": "Authenticated user is neither the player nor a linked parent.",
  "timestamp": "2026-03-31T12:00:00Z"
}
```

---

## Breaking Changes & Deprecations

### Removed Endpoints

1. `GET /api/integrations/me/match-status` → **Removed**
   - Old purpose: Check if player had confirmed identity match
   - New behavior: No confirmation needed; lookup is stateless
   
2. `POST /api/integrations/me/matches/confirm` → **Removed**
   - Old purpose: Player confirms which identity record to use
   - New behavior: All records displayed; no confirmation

3. `GET /api/integrations/me/matches` → **Removed** (if it existed)
   - Old purpose: Candidate list for confirmation
   - New behavior: Direct career lookup returns all records

### Migration Path for Existing Clients

- **Old flow** (removed): `/api/integrations/me/match-status` → confirm with POST → view in integrated-history
- **New flow** (added): `GET /api/players/{playerId}/seasons` → view all records inline

### Frontend Impact

- **Remove**: "Integrated History" page (*/integrated-history route)
- **Remove**: Confirmation modal/flow
- **Add**: "Seasons" tab in player profile
- **Add**: Call to new `GET /api/players/{playerId}/seasons` endpoint
- **Update**: Season display logic to show source attribution for each row

---

## Performance Considerations

### Query Timeout

- **Timeout**: 30 seconds per query (configurable)
- **Default**: 10 seconds (typical response < 1 second with proper indexes)
- **When triggered**: Database is slow or career tables have millions of rows without indexes
- **Response**: 504 status with friendly error message + retry option

### Cache Hit Rate

- **Expected**: 70-80% of requests served from browser cache (same user, within 5 min)
- **Benefit**: Reduces backend load significantly
- **Trade-off**: 5-minute data lag acceptable for read-only player history

### Database Indexes

- **Required**: Functional index on `LOWER(TRIM(player_name))` for each career table
- **Without index**: Query may table-scan → slow
- **With index**: Query should execute <100ms for typical player (0-20 records)

---

## Related Endpoints (Not Changed)

These existing endpoints remain unchanged and can be called alongside the new Seasons endpoint:

- `GET /api/players/{playerId}` - Get player profile (no changes)
- `GET /api/players/{playerId}/seasons/{seasonId}/games` - Get games for a season (existing, separate flow)
- `POST /api/players/{playerId}/seasons/{seasonId}/export` - Export season to PDF (separate, uses different data)

