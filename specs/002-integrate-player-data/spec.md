# Feature Specification: Simplified Player Data Integration (Redesign)

**Feature Branch**: `002-integrate-player-data`  
**Created**: 2026-03-31  
**Status**: Draft  
**Input**: User description: "Totally redesign integrate-player-data: (1) Remove the separate integrated-history page and all related API, link table, backend and frontend. (2) Make the 'seasons' tab query *_player_career tables sorted by season years, include all hockey stats."

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

**Why this priority**: This is the core value of the redesign—immediate, frictionless access to player history consolidated across all sources.

**Independent Test**: Can be fully tested by loading career data into THF/AYHL/AHF tables, signing in as a player with matching name + birthdate, navigating to the Seasons tab, and verifying all matching records appear with correct stats.

**Acceptance Scenarios**:

1. **Given** THF, AYHL, and AHF career data are loaded and contain records matching the player's name and birthdate, **When** the player logs in and navigates to the Seasons tab, **Then** all matching records from available sources are displayed in a single list sorted by season year (newest first).
2. **Given** a player has records in only 2 of 3 sources, **When** the Seasons tab loads, **Then** records from all available sources are shown and missing sources are clearly indicated.
3. **Given** the Seasons tab is open, **When** the player selects a specific season, **Then** all team and game statistics for that season are displayed (jersey, GP, goals, assists, points, penalties, PIM).
4. **Given** multiple seasons are available, **When** the player views season history, **Then** seasons are displayed in chronological order (most recent first).
5. **Given** a player with no matching career records logs in, **When** the Seasons tab loads, **Then** a user-friendly message indicates no historical records were found.

---

### User Story 2 - Parent Views Child Season History (Priority: P2)

As a parent with access to a child account, I want to view the same season and career data in the Seasons tab so I can monitor the child's team progression and statistics.

**Why this priority**: Parent visibility is central to the product use-case and uses the same simplified career lookup as the player.

**Independent Test**: Can be tested by logging in as a parent account linked to a player, navigating to the Seasons tab, and verifying all career records appear with correct attribution.

**Acceptance Scenarios**:

1. **Given** a parent is linked to a player account with career records, **When** the parent views the child's profile and opens the Seasons tab, **Then** the parent sees all available season and team data in the same format as the player would see.

---

### User Story 3 - Career Data Import and Refresh (Priority: P3)

As a system operator, I want to periodically import and refresh career data from THF, AYHL, and AHF sources so that player records remain current.

**Why this priority**: Ensures long-term data freshness, but is decoupled from the run-time lookup logic.

**Independent Test**: Can be tested by running an import process, verifying records appear in career tables, and confirming that newly loaded records become immediately visible to players via Seasons tab lookup.

**Acceptance Scenarios**:

1. **Given** new career data is available from a source, **When** the import process runs, **Then** records are loaded/updated in the appropriate career table.
2. **Given** a player logs in after a career data refresh, **When** the Seasons tab loads, **Then** it reflects the most recent imported data.

### Edge Cases

- Player's name/birthdate combination matches multiple records in a single source (e.g., duplicate THF entries).
- Player's name appears in source data with spelling variations or middle initials not in the player's profile.
- Birth month-year in player profile is incomplete or missing.
- A season appears in multiple sources with conflicting team or stat information for the same source record.
- Career tables are empty or contain no matching records.
- Player profile data is updated after initial login (name/birthdate change).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST remove the `player_identity_map` table and all backend services that manage persistent match linking.
- **FR-002**: System MUST remove the "Integrated History" page (`/integrated-history`) and all related frontend components.
- **FR-003**: System MUST remove `/api/integrations/me/match-status` and `/api/integrations/me/matches/confirm` endpoints.
- **FR-004**: System MUST modify the "Seasons" tab to perform runtime lookup of career records by player name + birthdate from AYHL, THF, and AHF tables.
- **FR-005**: System MUST return all matching career records from available sources in a single unified response, sorted by season year (most recent first).
- **FR-006**: System MUST include all hockey statistics in the Seasons tab display: source, sourcePlayerId, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, PIM.
- **FR-007**: System MUST handle multiple records per source gracefully (e.g., if THF has duplicate entries for the same player-season, display all matches and note the duplication in UI).
- **FR-008**: System MUST clearly indicate which sources have data available and which do not.
- **FR-009**: System MUST display a user-friendly message when no career records match the player's name + birthdate combination.
- **FR-010**: System MUST ensure career data lookup respects the player's current profile name and birthdate (not cached/persisted links).
- **FR-011**: System MUST support parent viewing of child career data through the same Seasons tab mechanism.
- **FR-012**: System MUST allow career data to be imported/refreshed from external sources (THF, AYHL, AHF) via operator-initiated or scheduled daily import processes.
- **FR-013**: System MUST delete or deprecate all identity_map related database structures and application code.
- **FR-014**: System MUST remove all candidate confirmation UI components and related frontend endpoints for match ambiguity resolution.
- **FR-015**: System MUST maintain data integrity by ensuring career table records include source attribution for each entry.

### Key Entities *(include if feature involves data)*

- **Player Profile**: Full name, birthdate (used for career lookup at runtime).
- **Career Tables** (AYHL, THF, AHF): source, sourcePlayerId, season, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, PIM, and other relevant hockey statistics.
- **Seasons View Response**: Unified response containing all matching records across sources with season grouping and statistics.

---

## Success Criteria

1. **Immediate Data Access**: Players see their complete career history in the Seasons tab within 1-2 seconds of page load, with no confirmation steps.
2. **Data Completeness**: All available hockey statistics (10+ stat fields) are visible for each team record without requiring additional clicks.
3. **Source Transparency**: Each record clearly shows its source (AYHL, THF, AHF) and source ID for traceability.
4. **Graceful Degradation**: If one source has no data, the tab still displays data from other sources and indicates the gap.
5. **Cleanup Completion**: All code, tables, and UI components related to the old integrated-history and identity_map approach are removed.
6. **No Breaking Changes**: Existing player profile functionality, authentication, and parent access remain unchanged.

## Assumptions

- Career tables (ayhl_player_career, thf_player_career, ahf_player_career) already exist and are regularly updated via import processes.
- Player profile includes name and birthdate fields used for lookup.
- No persistent linking is needed; parent/player access is managed via existing account authorization system.
- All hockey stats are already stored in career tables with consistent column naming across sources.
- UI will display duplicates/conflicts as-is (up to frontend presentation logic, not backend filtering).

## Dependencies & Scope Boundaries

- **Out of Scope**: 
  - Changes to career table schema or data ingestion pipelines
  - Fuzzy name matching or nickname resolution
  - Conflict resolution for duplicate records (display only)
  
- **In Scope**: 
  - Backend API redesign to replace identity_map lookup with direct career table query
  - Frontend redesign of Seasons tab to call new endpoint and display all stats
  - Database schema cleanup: remove identity_map table and related constraints
  - Deprecation and removal of all integrated-history related code paths

