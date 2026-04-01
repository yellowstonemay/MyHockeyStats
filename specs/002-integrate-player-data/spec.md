# Feature Specification: Simplified Player Data Integration (Redesign)

**Feature Branch**: `002-integrate-player-data`  
**Created**: 2026-03-31  
**Status**: Draft  
**Input**: User description: "Totally redesign integrate-player-data: (1) Remove the separate integrated-history page and all related API, link table, backend and frontend. (2) Make the 'seasons' tab query *_player_career tables sorted by season years, include all hockey stats."

## Clarifications

### Session 2026-03-31

- Q: When matching a player to career records, should the system match by "name + birthdate" or just "name"? What is the matching logic (exact vs fuzzy)? -> A: Match by name only using exact normalized match (lowercase, whitespace trimmed, punctuation removed). Birthdate excluded because many career data sources do not provide it.
- Q: How should the system handle lookup failures and ambiguous matches (multiple players with same normalized name)? -> A: Display all matching records with an ambiguity note ("Multiple players found with this name. Verify by team/season."). For database errors/timeouts, show friendly error message with retry option.
- Q: How should parent access to child career data be managed? -> A: Use existing account link system. Seasons endpoint returns data if authenticated user is the player OR a linked parent of the player account.
- Q: Should the Seasons tab cache career data or always fetch fresh? -> A: Use HTTP cache headers (Cache-Control: private, max-age=300) to cache for 5 minutes in browser, allowing session reuse without backend hits. Career data doesn't change minute-to-minute; 5-min freshness is acceptable.
- Q: How should the Seasons tab display when same player appears in multiple sources for same season with different teams/stats? -> A: Display as separate rows per source with clear source attribution and all stats. User can easily compare across sources transparently.

## Overview

This is a complete redesign of the player data integration feature. Instead of a separate "integrated-history" page with candidate confirmation and persistent linking via identity_map table, we consolidate everything into a single "Seasons" tab that directly queries career tables (AYHL, THF, AHF) based on the player's profile name and birthdate, and displays all stats directly.

### Architecture Changes

- **Removed**: 
  - `player_identity_map` table (no persistent linking)
  - "Integrated History" page (/integrated-history)
  - "Confirm Your Player Record" ambiguity selector UI
  - `/api/integrations/me/match-status` endpoint
  - `/api/integrations/me/matches/confirm` endpoint
  - Related backend services for candidate management
  
- **Consolidated Into**: 
  - "Seasons" tab in player profile
  - Direct career table lookup by name + birthdate at runtime
  - Single unified season/team/game view with all stats

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Player Views Career History from All Sources in One Tab (Priority: P1)

As a logged-in player, I want to view my complete career history aggregated from THF, AYHL, and AHF in a single "Seasons" tab without any confirmation steps, so I can immediately see all my past teams and game statistics.

Note: Career data is matched by player name only (exact normalized match: lowercase, whitespace trimmed, punctuation removed) since many data sources do not consistently provide birthdate.

**Why this priority**: This is the core value of the redesign—immediate, frictionless access to player history consolidated across all sources.

**Independent Test**: Can be fully tested by loading career data into THF/AYHL/AHF tables, signing in as a player with matching name + birthdate, navigating to the Seasons tab, and verifying all matching records appear with correct stats.

**Acceptance Scenarios**:

1. **Given** THF, AYHL, and AHF career data are loaded and contain records matching the player's full name (normalized), **When** the player logs in and navigates to the Seasons tab, **Then** all matching records from available sources are displayed in a single list sorted by season year (newest first).
2. **Given** a player has records in only 2 of 3 sources, **When** the Seasons tab loads, **Then** records from all available sources are shown and missing sources are clearly indicated.
3. **Given** the Seasons tab is open, **When** the player selects a specific season, **Then** all team and game statistics for that season are displayed (jersey, GP, goals, assists, points, penalties, PIM).
4. **Given** multiple seasons are available, **When** the player views season history, **Then** seasons are displayed in chronological order (most recent first).
5. **Given** a player with no matching career records logs in, **When** the Seasons tab loads, **Then** a user-friendly message indicates no historical records were found.
6. **Given** multiple players share the same normalized name and records match the profile name, **When** the Seasons tab loads, **Then** all matching records are displayed with an ambiguity note (e.g., "Multiple players found with this name. Verify by team/season.").

---

### User Story 2 - Parent Views Child Season History (Priority: P2)

As a parent with access to a child account (via existing account linking system), I want to view the same season and career data in the Seasons tab so I can monitor the child's team progression and statistics.

Note: Parent access reuses existing account link infrastructure; no new linking mechanism required.

**Why this priority**: Parent visibility is central to the product use-case and uses the same simplified career lookup as the player.

**Independent Test**: Can be tested by logging in as a parent account linked to a player, navigating to the Seasons tab, and verifying all career records appear with correct attribution.

**Acceptance Scenarios**:

1. **Given** a parent is linked to a player account (via existing platform account linking), **When** the parent views the child's profile and opens the Seasons tab, **Then** the parent sees all available season and team data in the same format as the player would see.

---

### User Story 3 - Career Data Import and Refresh (Priority: P3)

As a system operator, I want to periodically import and refresh career data from THF, AYHL, and AHF sources so that player records remain current.

**Why this priority**: Ensures long-term data freshness, but is decoupled from the run-time lookup logic.

**Independent Test**: Can be tested by running an import process, verifying records appear in career tables, and confirming that newly loaded records become immediately visible to players via Seasons tab lookup.

**Acceptance Scenarios**:

1. **Given** new career data is available from a source, **When** the import process runs, **Then** records are loaded/updated in the appropriate career table.
2. **Given** a player logs in after a career data refresh, **When** the Seasons tab loads, **Then** it reflects the most recent imported data.

### Edge Cases

- Player's name matches multiple records in a single source (e.g., duplicate THF entries for same name).
- Player's name appears in source data with spelling variations or middle initials not exactly in the player's profile.
- Multiple players with the same or similar names (after normalization) exist in career tables.
- Same season may appear in multiple source rows (e.g., player in both AYHL and THF in "2024-2025 Regular Season") with different teams or stats.
- System displays all records as separate rows per source with full stats; no filtering or conflict resolution.
- Career tables are empty or contain no matching records.
- Player profile data is updated after initial login (name/birthdate change).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST remove the `player_identity_map` table and all backend services that manage persistent match linking.
- **FR-002**: System MUST remove the "Integrated History" page (`/integrated-history`) and all related frontend components.
- **FR-003**: System MUST remove `/api/integrations/me/match-status` and `/api/integrations/me/matches/confirm` endpoints.
- **FR-004**: System MUST modify the "Seasons" tab to perform runtime lookup of career records by player name (exact normalized match: lowercase, whitespace trimmed, common punctuation removed) from AYHL, THF, and AHF tables.
- **FR-005**: System MUST return all matching career records from available sources in a single unified response, sorted by season year (most recent first).
- **FR-006**: System MUST include all hockey statistics in the Seasons tab display: source, sourcePlayerId, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, PIM.
- **FR-007**: System MUST display all matching records from all sources in separate rows, even when the same season appears in multiple sources. Each row includes source attribution and full stats for comparison.
- **FR-008**: System MUST clearly indicate which sources have data available and which do not.
- **FR-009**: System MUST display a user-friendly message and retry option when no career records match the player's name or when database/lookup errors occur.
- **FR-010**: System MUST display all matching career records with an ambiguity notice ("Multiple players found with this name. Verify by team/season.") when multiple players share the same exact normalized name.
- **FR-011**: System MUST handle database timeouts gracefully by showing error message with retry mechanism instead of indefinite loading.
- **FR-012**: System MUST ensure career data lookup uses the player's current profile name (not cached/persisted links) with exact normalized matching rules applied consistently.
- **FR-013**: System MUST support parent viewing of child career data using existing account authorization system (parent account must be linked to player account).
- **FR-014**: System MUST allow career data to be imported/refreshed from external sources (THF, AYHL, AHF) via operator-initiated or scheduled daily import processes.
- **FR-015**: System MUST delete or deprecate all identity_map related database structures and application code.
- **FR-016**: System MUST remove all candidate confirmation UI components and related frontend endpoints for match ambiguity resolution.
- **FR-017**: System MUST maintain data integrity by ensuring career table records include source attribution for each entry.
- **FR-018**: System MUST use HTTP cache headers (`Cache-Control: private, max-age=300`) on Seasons endpoint to allow browser caching for 5 minutes, reducing backend load while maintaining acceptable data freshness.

### Key Entities *(include if feature involves data)*

- **Player Profile**: Full name (used for career lookup at runtime via exact normalized match).
- **Career Tables** (AYHL, THF, AHF): source, sourcePlayerId, season, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, PIM, and other relevant hockey statistics.
- **Seasons View Response**: Unified response containing all matching records across sources with season grouping and statistics.

---

## Success Criteria

1. **Immediate Data Access**: Players see their complete career history in the Seasons tab within 1-2 seconds of page load, with no confirmation steps.
2. **Data Transparency**: Each record in Seasons tab displays complete source attribution (source name, sourcePlayerId) and all available stats without filtering or merging across sources.
2. **Data Transparency**: Each record in Seasons tab displays complete source attribution (source name, sourcePlayerId) and all available stats without filtering or merging across sources.
4. **Graceful Degradation**: If one source has no data, the tab still displays data from other sources and indicates the gap.
5. **Error Transparency**: Lookup failures display friendly error messages with retry options; ambiguous matches display all records with clear notes.
6. **Cleanup Completion**: All code, tables, and UI components related to the old integrated-history and identity_map approach are removed.
6. **No Breaking Changes**: Existing player profile functionality, authentication, and parent access remain unchanged.

## Assumptions

- Career tables (ayhl_player_career, thf_player_career, ahf_player_career) already exist and are regularly updated via import processes.
- Player profile includes name field used for lookup (birthdate may be present but is not used for matching).
- No persistent linking is needed; parent/player access is managed via existing account link/authorization system (parents already linked to player accounts).
- All hockey stats are already stored in career tables with consistent column naming across sources.
- UI will display duplicates/conflicts as-is (up to frontend presentation logic, not backend filtering).

## Dependencies & Scope Boundaries

- **Out of Scope**: 
  - Changes to career table schema or data ingestion pipelines
  - Fuzzy name matching or nickname resolution
  - Conflict resolution for duplicate records (display only)
  - New parent-child linking mechanism (uses existing account links)
  
- **In Scope**: 
  - Backend API redesign to replace identity_map lookup with direct career table query
  - Frontend redesign of Seasons tab to call new endpoint and display all stats
  - Database schema cleanup: remove identity_map table and related constraints
  - Deprecation and removal of all integrated-history related code paths
  - Authorization check in backend to permit access for player + linked parents

