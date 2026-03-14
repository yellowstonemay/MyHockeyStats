# Feature Specification: Integrate Player Data Sources

**Feature Branch**: `001-integrate-player-data`  
**Created**: 2026-03-12  
**Status**: Draft  
**Input**: User description: "data integration: now i have data dumped from thf, ayhl and gamesheet. load them into database and when user login, we should be able find related user information from those data by name and birthday(yy-mm)"

## Clarifications

### Session 2026-03-12

- Q: When multiple imported players share the same normalized full name + birth month-year, what should the system do at login? -> A: Show all candidate records to the user and let them choose.
- Q: After a user selects the correct candidate from an ambiguous match list, how should future logins behave? -> A: Save a persistent match link and auto-use it on future logins unless source identity changes.
- Q: If the same logical game/player stat conflicts across sources, what should the user see? -> A: Show source-attributed values and flag the conflict.
- Q: What matching rule should be used for login-time identity lookup by name + birth month-year? -> A: Fuzzy name match (typos/nicknames) with exact birth month-year.
- Q: How frequently should source data imports be expected to run for this feature's Definition of Done? -> A: Daily batch import.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Player Sees Linked Historical Data at Login (Priority: P1)

As a logged-in player, I want my profile to be matched to historical records from THF, AYHL, and GameSheet using my name and birth month-year so I can immediately view my past teams and game activity.

**Why this priority**: This is the core value of the feature. Without accurate matching and retrieval, imported data does not benefit users.

**Independent Test**: Can be fully tested by importing source datasets, signing in with a known player account, and verifying that matching records from all available sources are shown under that player.

**Acceptance Scenarios**:

1. **Given** THF, AYHL, and GameSheet data are loaded, **When** a player with a matching full name and birth month-year logs in, **Then** the system returns all matched records tied to that player account.
2. **Given** data is loaded and only two of three sources contain records for a player, **When** the player logs in, **Then** the system shows available records and indicates which source has no match.
3. **Given** multiple candidate records share the same normalized name and birth month-year, **When** the player logs in, **Then** the system presents candidate records and requires explicit user selection before linking.
4. **Given** the player previously selected a candidate record, **When** the player logs in again and source identity signals remain consistent, **Then** the system automatically reuses the saved match link without re-prompting.
5. **Given** two sources provide conflicting values for the same logical stat, **When** matched data is shown, **Then** the system displays source-attributed values and marks the conflict clearly.
6. **Given** no exact normalized name match exists but a fuzzy name candidate exists with the same birth month-year, **When** the player logs in, **Then** the system includes that candidate in ambiguity handling for user confirmation.

---

### User Story 2 - Parent Views Child Data Through Linked Access (Priority: P2)

As a parent with authorized access to a child account, I want to see the same matched historical records so I can review team and game history without switching systems.

**Why this priority**: Parent visibility is a primary product role and supports shared decision-making and season tracking.

**Independent Test**: Can be tested by logging in as a linked parent account and verifying that the same matched player dataset is displayed read-only.

**Acceptance Scenarios**:

1. **Given** a parent is linked to a player account with matched records, **When** the parent logs in and opens the child profile, **Then** the parent can view the child data aggregated from available sources.

---

### User Story 3 - Admin/Operator Loads New Data Drops (Priority: P3)

As a system operator, I want to load new THF, AYHL, and GameSheet data drops into the platform so user-facing information stays current.

**Why this priority**: Reliable refresh of source data ensures long-term usefulness, but can be delivered after login matching behavior.

**Independent Test**: Can be tested by loading a new dataset, running validation checks, and confirming updated records are available for subsequent user logins.

**Acceptance Scenarios**:

1. **Given** a valid data drop for one or more supported sources, **When** the operator runs the import process, **Then** new records are loaded and become queryable for user matching.
2. **Given** a malformed data file, **When** import is attempted, **Then** the system rejects invalid records and provides a clear error summary without removing previously valid data.

### Edge Cases

- Two different players share the same full name and birth month-year across source files.
- A user account has incomplete identity data (missing birth month-year or name formatting issues).
- Source files use inconsistent name formatting (middle initials, suffixes, punctuation, extra whitespace).
- Source files may include typos or nickname variations that require fuzzy name matching.
- A player appears in multiple sources with partially conflicting team or game details.
- Data for a player exists in a source dump but login occurs before that source has been imported.
- A previously saved match link may no longer be reliable when source identity signals change.
- The same game or stat may disagree across THF, AYHL, and GameSheet sources.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow operator-triggered ingestion of THF, AYHL, and GameSheet data dumps into persistent platform storage.
- **FR-002**: System MUST validate incoming records and reject malformed entries while preserving successfully loaded valid records.
- **FR-003**: System MUST record source provenance for each imported player/team/game record so users can see where each record originated.
- **FR-004**: System MUST match a logged-in player account to imported records using exact birth month-year and name matching that supports both normalized exact and fuzzy name candidates.
- **FR-005**: System MUST return matched records from all available sources in a unified player view immediately after successful login.
- **FR-006**: System MUST handle missing-source matches by showing available data and explicitly identifying unavailable source results.
- **FR-007**: System MUST prevent cross-account exposure by ensuring only the authenticated player and authorized linked parent accounts can access a player's matched records.
- **FR-008**: System MUST support repeat imports without creating duplicate logical records for the same player/source/event combination.
- **FR-009**: System MUST log import outcomes, including total records processed, accepted, rejected, and duplicate-skipped counts.
- **FR-010**: System MUST provide user-visible messaging when no matching records are found for the authenticated account identity.
- **FR-011**: System MUST present all same-identity candidates to the authenticated user when ambiguity exists and require explicit selection before creating or updating a match link.
- **FR-012**: System MUST persist user-confirmed match links and automatically apply them on future logins when identity signals remain consistent.
- **FR-013**: System MUST invalidate or suspend automatic reuse of a saved match link when source identity signals materially change and require user re-confirmation.
- **FR-014**: System MUST display conflicting values with source attribution and a clear conflict indicator rather than silently overwriting one source with another.
- **FR-015**: System MUST constrain fuzzy name matching to exact birth month-year matches and route fuzzy results through explicit user confirmation before linking.
- **FR-016**: System MUST support a scheduled daily batch import for THF, AYHL, and GameSheet sources, with per-source success/failure reporting.

### Key Entities *(include if feature involves data)*

- **User Account Identity**: Player account identity attributes used for matching, including full name, birth month-year, and account role relationships.
- **Imported Player Record**: External player-level data from THF, AYHL, or GameSheet including source identifier, player name variants, and season context.
- **Imported Game Record**: Game-level historical detail linked to an imported player record, such as game date, opponent, outcome, and individual stat indicators.
- **Match Link**: Auditable association between a user account identity and one or more imported player records, including match confidence/state and last verified timestamp.
- **Import Run Summary**: Import execution metadata containing source, run time, processed counts, accepted/rejected totals, and error details.

### Assumptions

- Existing authentication and parent-child account linking remain in place and are reused by this feature.
- Birthdate matching is intentionally scoped to birth month-year as requested, not full birthdate.
- Source dumps are provided in a parseable structured format before ingestion begins.
- When conflicting values exist across sources, records remain source-attributed rather than merged into a single "authoritative" value during this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 95% of user accounts with corresponding records in source dumps are automatically matched on login without manual intervention.
- **SC-002**: 100% of successful imports produce a run summary including processed, accepted, rejected, and duplicate-skipped counts.
- **SC-003**: For matched users, aggregated results from available sources are displayed within 3 seconds for at least 95% of login sessions.
- **SC-004**: In user acceptance testing, at least 90% of players and parents can confirm that displayed team/game history corresponds to expected historical records.
- **SC-005**: Unauthorized cross-account data exposure incidents related to this feature remain at zero in production monitoring after release.
- **SC-006**: At least 95% of daily scheduled import runs complete successfully for each configured source over a rolling 30-day window.
