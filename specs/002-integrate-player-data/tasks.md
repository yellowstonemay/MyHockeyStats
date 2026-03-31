# Implementation Tasks: Simplified Player Data Integration (002-integrate-player-data)

**Feature**: 002-integrate-player-data  
**Branch**: `002-integrate-player-data`  
**Status**: Ready for Implementation  
**Created**: 2026-03-31  
**Audience**: Backend developers, frontend developers, QA

---

## Overview

This document contains all actionable implementation tasks for the simplified player data integration feature. The feature eliminates persistent identity mapping and replaces it with a stateless runtime career lookup in the "Seasons" tab.

**Key Outputs**:
- Remove `player_identity_map` table and all related API endpoints
- Implement new `CareerLookupService` for normalized name matching
- Add `GET /api/players/{playerId}/seasons` endpoint with HTTP caching
- Replace old "Integrated History" page with new "Seasons" tab in player profile
- Support parent access via existing account link system

**Team**: 1 backend developer + 1 frontend developer (can work in parallel)  
**Estimated Duration**: 10-14 days  
**Dependencies**: All previous user stories (001-youth-homepage) must be complete

---

## Dependency Graph

```
Phase 1 (Setup)
    ↓
Phase 2 (Backend) [US1] ← can run in parallel with Phase 3 (Frontend)
    ↓
Phase 4 (Parent Access) [US2]
    ↓
Phase 5 (Testing & Cleanup)
    ↓
Phase 6 (Deployment)
```

**Parallelizable Work**:
- Backend repository methods [T005-T007] can be implemented in parallel
- Frontend component [T013] can start as soon as DTOs are defined [T004]
- API integration tests [T020] can start immediately after endpoint is implemented [T009]

---

## Phase 1: Setup & Foundational (Database & Core DTOs)

### Goal
Set up database schema changes and define core data transfer objects that all subsequent work depends on.

### Independent Test Criteria
- Database migration applies without errors
- Career lookup indexes are created successfully
- All DTOs compile and serialize/deserialize correctly to JSON

---

- [ ] T001 Create database migration to add functional indexes on normalized player name in backend/src/main/resources/db/migration/V[X]__add_career_lookup_indexes.sql

- [ ] T002 Create database migration to mark player_identity_map table as deprecated in backend/src/main/resources/db/migration/V[X+1]__deprecate_identity_map_table.sql

- [ ] T003 [P] Create SeasonCareerRecordDto in backend/src/main/java/com/myhockeystats/api/dto/integration/SeasonCareerRecordDto.java (source, sourcePlayerId, playerName, season, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, pim, importedAt, isAmbiguousMembership, ambiguityNote)

- [ ] T004 [P] Create SeasonsResponseDto in backend/src/main/java/com/myhockeystats/api/dto/integration/SeasonsResponseDto.java (playerId, playerName, records list, hasAmbiguity, ambiguityNote, availableSources set, emptySources set, fetchedAt, cacheControl)

---

## Phase 2: Backend Implementation [US1]

### Goal
Implement core career lookup service, repository methods, and Seasons endpoint with authorization and caching.

### Independent Test Criteria
- CareerLookupService normalizes names correctly
- Repository queries return records matching normalized name
- SeasonsController endpoint returns 200 with all matching records
- SeasonsController endpoint returns 401 for unauthorized users
- SeasonsController endpoint includes Cache-Control headers
- Authorization checks both player self-access and parent access

---

- [ ] T005 [P] [US1] Add findByNormalizedName repository method in backend/src/main/java/com/myhockeystats/repository/AyhlPlayerCareerRepository.java with @Query() using LOWER(TRIM(r.playerName))

- [ ] T006 [P] [US1] Add findByNormalizedName repository method in backend/src/main/java/com/myhockeystats/repository/ThfPlayerCareerRepository.java with @Query() using LOWER(TRIM(r.playerName))

- [ ] T007 [P] [US1] Add findByNormalizedName repository method in backend/src/main/java/com/myhockeystats/repository/AhfPlayerCareerRepository.java with @Query() using LOWER(TRIM(r.playerName))

- [ ] T008 [US1] Create CareerLookupService in backend/src/main/java/com/myhockeystats/service/integration/CareerLookupService.java with:
  - normalizePlayerName(String fullName) static method (lowercase, trim, remove ['-\.], normalize whitespace)
  - lookupCareerRecordsByName(String playerName) method that queries all three repositories
  - toSeasonDtos(List<?> records, String source) helper to convert entities to DTOs
  - ambiguity detection logic (multiple distinct sourceIds for same name)
  - sorting by season DESC then source ASC

- [ ] T009 [US1] Create SeasonsController endpoint in backend/src/main/java/com/myhockeystats/api/SeasonsController.java:
  - GET /api/players/{playerId}/seasons endpoint (authentication required)
  - Extract playerId from path
  - Call IntegrationAccessGuard.canViewSeasons(userId, playerId) for authorization
  - Call CareerLookupService.lookupCareerRecordsByName(playerProfile.getName())
  - Return SeasonsResponseDto with Cache-Control header (private, max-age=300)
  - Handle 401 Unauthorized, 404 Player Not Found errors

- [ ] T010 [US1] Extend IntegrationAccessGuard in backend/src/main/java/com/myhockeystats/security/IntegrationAccessGuard.java:
  - Add canViewSeasons(String userId, String playerId) method
  - Check userId == playerId (player viewing own data)
  - Check AccountLink.findByParentIdAndChildId(userId, playerId).isPresent() (parent viewing child)
  - Return boolean indicating authorization

- [ ] T011 [US1] Update PlayerProfileService in backend/src/main/java/com/myhockeystats/service/PlayerProfileService.java:
  - Ensure getPlayerProfile(String playerId) returns complete Player with name field populated
  - Used by SeasonsController to get player name for career lookup

- [ ] T012 [US1] Create unit tests in backend/src/test/java/com/myhockeystats/service/integration/CareerLookupServiceTest.java:
  - Test normalizePlayerName() with various inputs (hyphenated names, apostrophes, multiple spaces)
  - Test lookupCareerRecordsByName() returns union of records from all three sources
  - Test ambiguity detection when multiple sourceIds found
  - Test sorting by season DESC
  - Test empty results handling
  - Coverage target: > 80%

- [ ] T013 [US1] Create integration tests in backend/src/test/java/com/myhockeystats/api/SeasonsControllerIntegrationTest.java:
  - Test GET /api/players/{playerId}/seasons returns 200 with all matching records
  - Test authorization: player can view own data (200), non-player cannot (401)
  - Test Cache-Control header is present and correct
  - Test ambiguous match scenario (multiple sourceIds)
  - Test no records found scenario (empty career tables)
  - Test database timeout/error handling (500 with friendly message)
  - Use Testcontainers for PostgreSQL + pre-load career table data
  - Coverage target: > 80%

---

## Phase 3: Frontend Implementation [US1]

### Goal
Create new Seasons tab component, integrate with new API endpoint, and remove old integration-related UI.

### Independent Test Criteria
- SeasonsTab component renders career records with source attribution
- API integration fetches and displays all records
- Loading and error states are handled gracefully
- Ambiguity message displays when multiple players found
- No browser console errors or warnings

---

- [ ] T014 [P] [US1] Create SeasonsTab component in frontend/web/src/components/PlayerProfile/SeasonsTab.jsx:
  - Display list of career records from API response
  - Show source attribution (AYHL, THF, AHF) for each record
  - Show all statistics: season, club, team, jerseyNumber, gamesPlayed, goals, assists, points, penalties, pim
  - Show season year as header with count of records for that season
  - Handle loading state with spinner/skeleton
  - Handle error state with user-friendly message and retry button
  - Show ambiguity note if hasAmbiguity flag is true
  - Sort records by season DESC (newest first)

- [ ] T015 [P] [US1] Extend API client in frontend/web/src/lib/api.js:
  - Add fetchPlayerSeasons(playerId, accessToken) function
  - Call GET /api/players/{playerId}/seasons with Bearer token
  - Handle HTTP 401 Unauthorized (redirect to login)
  - Handle HTTP 404 Not Found (return empty response)
  - Handle HTTP 500 or timeout (return error state for UI)
  - Include error handling with user-friendly messages

- [ ] T016 [US1] Integrate SeasonsTab into PlayerProfile component in frontend/web/src/pages/PlayerProfile.jsx:
  - Add SeasonsTab to tab navigation (alongside Profile Settings, Account Linking, etc.)
  - Pass playerId to SeasonsTab component
  - Call fetchPlayerSeasons() on tab activation
  - Display loading/error/success states from API response
  - Maintain existing tab functionality for other tabs

- [ ] T017 [US1] Add Jest unit tests in frontend/web/src/components/PlayerProfile/SeasonsTab.test.jsx:
  - Test component renders career records from props
  - Test loading state displays spinner
  - Test error state displays retry button
  - Test ambiguity message displays when flag is true
  - Test sorting by season DESC
  - Test source attribution displays correctly
  - Coverage target: > 80%

- [ ] T018 [US1] Add React Integration tests in frontend/web/src/pages/PlayerProfile.integration.test.jsx:
  - Test SeasonsTab tab is clickable and loads data
  - Test fetchPlayerSeasons() is called with correct playerId
  - Test career records display after successful API call
  - Test error handling when API returns 500
  - Mock axios (or fetch) to simulate API responses
  - Coverage target: > 80%

---

## Phase 4: Parent Access [US2]

### Goal
Extend Seasons endpoint to support parent viewing of linked child data using existing account authorization system.

### Independent Test Criteria
- Parent account linked to player account can view child's seasons
- Non-linked parent cannot view seasons (401 Unauthorized)
- Player can still view own seasons
- Authorization check uses existing AccountLink table

---

- [ ] T019 [US2] Extend SeasonsController authorization in backend/src/main/java/com/myhockeystats/api/SeasonsController.java:
  - Update GET /api/players/{playerId}/seasons endpoint to call IntegrationAccessGuard.canViewSeasons()
  - Verify IntegrationAccessGuard.canViewSeasons() checks both player self-access and parent link
  - Return 401 Unauthorized if user is neither player nor linked parent
  - Log authorization checks for audit (INFO level)

- [ ] T020 [US2] Add parent access integration tests in backend/src/test/java/com/myhockeystats/api/SeasonsControllerParentAccessTest.java:
  - Test parent linked to player can view child's seasons (200)
  - Test parent not linked to player cannot view seasons (401)
  - Test multiple linked children: parent sees data only for linked player (200)
  - Test child account linked to parent: player can view own data when logged in as parent account (depends on auth model)
  - Use Testcontainers + pre-populate AccountLink table for test data
  - Coverage target: > 80%

- [ ] T021 [US2] Update frontend API client in frontend/web/src/lib/api.js:
  - Ensure fetchPlayerSeasons(playerId, accessToken) works for both player and parent viewing
  - Parent views child profile: pass child's playerId
  - API authorization handled server-side (no frontend changes needed)
  - Test with parent account logged in viewing child profile

---

## Phase 5: Testing & Cleanup

### Goal
Ensure comprehensive testing coverage, remove all deprecated code/components, and validate data integrity.

### Independent Test Criteria
- All existing unit tests pass (>80% coverage)
- All integration tests pass with Testcontainers
- Old identity_map code is completely removed
- Old integrated-history page is removed
- No broken links or missing components
- Database cleanup scripts execute without errors

---

- [ ] T022 Add comprehensive end-to-end test in backend/src/test/java/com/myhockeystats/e2e/SeasonsE2ETest.java:
  - Load career data into all three tables (AYHL, THF, AHF)
  - Create player with matching name
  - Sign in as player
  - Call GET /api/players/{playerId}/seasons
  - Verify response includes records from all three sources
  - Verify ambiguity detection works
  - Verify parent access works
  - Use Testcontainers

- [ ] T023 [P] Remove deprecated IntegrationPlayerController methods in backend/src/main/java/com/myhockeystats/api/IntegrationPlayerController.java:
  - Remove /api/integrations/me/match-status endpoint
  - Remove /api/integrations/me/matches/confirm endpoint
  - Remove any candidate matching/confirmation logic
  - Keep only remaining integration endpoints (if any)

- [ ] T024 [P] Remove deprecated MatchConfirmation/MatchCandidateSelector components in frontend/web/src/components/:
  - Remove frontend/web/src/components/MatchConfirmation/ directory entirely
  - Remove frontend/web/src/components/MatchCandidateSelector/ directory entirely
  - Verify no other components import these removed components

- [ ] T025 [P] Remove integrated-history page in frontend/web/src/pages/:
  - Remove frontend/web/src/pages/IntegratedHistoryPage.jsx (or similar)
  - Remove route definition for /integrated-history from frontend/web/src/App.jsx or router config
  - Verify no navigation links point to /integrated-history

- [ ] T026 Clean up old identity_map related code in backend:
  - Search for all references to PlayerIdentityMap or similar class in backend/src/
  - Remove PlayerIdentityMap entity if exists: backend/src/main/java/com/myhockeystats/model/PlayerIdentityMap.java
  - Remove PlayerIdentityMapRepository: backend/src/main/java/com/myhockeystats/repository/PlayerIdentityMapRepository.java
  - Remove PlayerIdentityMapService: backend/src/main/java/com/myhockeystats/service/PlayerIdentityMapService.java
  - Remove related tests

- [ ] T027 Add database cleanup/deprecation documentation in specs/002-integrate-player-data/:
  - Create DATABASE_CLEANUP.md documenting steps to drop player_identity_map table in production
  - Include backup script before drop
  - Include rollback procedure
  - Include verification query to confirm drop was successful

- [ ] T028 [P] Update frontend/web/src/pages/PlayerProfile.jsx navigation:
  - Verify Seasons tab appears in main navigation after Profile Settings
  - Remove any old "Integrated History" tab references
  - Test tab navigation between Profile Settings, Account Linking, Seasons, etc.

- [ ] T029 Run full regression test suite:
  - Execute backend: `mvn clean test` and verify all tests pass
  - Execute frontend: `npm run test` and verify all Jest tests pass
  - Check test coverage reports (target > 80%)
  - Fix any failing tests before proceeding to deployment

---

## Phase 6: Deployment & Validation

### Goal
Build and deploy application with new feature, then validate correctness in staging/production.

### Independent Test Criteria
- Docker images build successfully
- Application starts without errors
- Seasons endpoint is accessible and functional
- Career data is visible in Seasons tab
- Public API documentation is updated
- No performance regressions

---

- [ ] T030 Update Docker build in backend/Dockerfile:
  - Ensure Dockerfile builds successfully with all new Java files
  - Run `docker build -t myhoceystats-backend:latest backend/` and verify build succeeds
  - Verify JAR includes all new CareerLookupService, SeasonsController classes

- [ ] T031 Update Docker build in frontend/web/Dockerfile:
  - Ensure Dockerfile builds React app with new SeasonsTab component
  - Run `docker build -t myhockeystats-frontend:latest frontend/web/` and verify build succeeds
  - Verify output includes new SeasonsTab.jsx compiled

- [ ] T032 [P] Update docker-compose.yml:
  - Verify docker-compose.yml includes both backend and frontend services
  - Add environment variables for database connection if needed
  - Run `docker-compose up` and verify both services start without errors
  - Access frontend at http://localhost and verify Seasons tab is visible

- [ ] T033 Validate Seasons endpoint in staging/preview:
  - Sign in as player
  - Navigate to Seasons tab
  - Verify career records display from all available sources
  - Verify ambiguity note displays if multiple players found
  - Verify no errors in browser console
  - Verify response times are < 1 second (p95)

- [ ] T034 [P] Update API documentation in backend/README.md or docs/:
  - Add REST API endpoint documentation for GET /api/players/{playerId}/seasons
  - Include request/response examples
  - Include authorization requirements
  - Include HTTP cache behavior (5-minute TTL)
  - Include error codes (401, 404, 500)

- [ ] T035 [P] Update frontend documentation in frontend/web/README.md:
  - Document new SeasonsTab component (props, usage)
  - Document API client function fetchPlayerSeasons(playerId, accessToken)
  - Document expected API response format
  - Include examples of rendering career data

- [ ] T036 Performance validation:
  - Measure Seasons endpoint response time with 100 career records (target < 1 second p95)
  - Verify HTTP cache is working (second load should hit browser cache)
  - Monitor database query time (target < 500ms)
  - Check for N+1 query problems

- [ ] T037 Smoke test full feature flow:
  - Create test player account
  - Seed career data in AYHL, THF, AHF tables for test player name
  - Sign in as player
  - Navigate to Seasons tab
  - Verify all career records display correctly
  - Verify parent (if linked) can view same data
  - Export stats if export feature is available
  - Verify no broken links or missing components

---

## Task Execution Guidelines

### Before Starting
1. Ensure all tasks from 001-youth-homepage are complete and merged to main branch
2. Create feature branch: `git checkout -b 002-integrate-player-data`
3. Pull latest database schema and career table definitions
4. Update `.github/copilot-instructions.md` with new feature context

### During Implementation
1. Mark tasks complete as you finish each one: replace `[ ]` with `[x]`
2. Commit after completing related task groups (e.g., all Phase 2 tasks)
3. Run tests frequently: `mvn clean test` (backend) and `npm run test` (frontend)
4. Keep comments in code for clarity (especially CareerLookupService normalization logic)

### After Each Phase
1. **Phase 1**: Run database migrations locally; verify indexes created
2. **Phase 2**: Run integration tests; verify endpoint returns data; verify authorization works
3. **Phase 3**: Run frontend tests; verify SeasonsTab renders correctly
4. **Phase 4**: Test parent access with linked account
5. **Phase 5**: Run full test suite; verify no broken links
6. **Phase 6**: Run smoke test; deploy to staging; monitor logs

### Code Review Checklist
- [ ] All PR commits reference task IDs (e.g., "T008: Implement CareerLookupService")
- [ ] All unit tests pass (coverage > 80%)
- [ ] All integration tests pass on Testcontainers
- [ ] No TODO comments left in code
- [ ] Authorization checks are in place (no accidental data exposure)
- [ ] Cache-Control headers present on Seasons endpoint
- [ ] DTOs are properly annotated (@Data, @Builder, @Getter, @Setter)
- [ ] No hardcoded URLs or credentials

### Known Gotchas
1. **Database Migration Numbering**: Check existing migration version (V*) before creating new ones; increment appropriately
2. **Functional Indexes**: Some PostgreSQL versions may not support functional indexes on computed expressions; ensure version >= 13
3. **Authorization Guard**: Ensure AccountLink table is populated with test data in integration tests (not done automatically)
4. **React Key Props**: SeasonsTab must use unique key for each record row (source + sourcePlayerId + season)
5. **API Response Format**: Ensure SeasonsResponseDto serialization matches contracts/api-contracts.md exactly

---

## Test Data Setup

### Prerequisite: Load Career Data for Testing

```sql
-- AYHL sample data
INSERT INTO ayhl_player_career (player_name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim, created_at, updated_at)
VALUES ('John Smith', '2024-2025', 'Ontario Minor Hockey', 'Toronto U18', 7, 32, 12, 8, 20, 4, 8, NOW(), NOW());

-- THF sample data
INSERT INTO thf_player_career (player_name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim, created_at, updated_at)
VALUES ('John Smith', '2024-2025', 'Thunder Bay Minor Hockey', 'Thunder Bay U18', 12, 28, 10, 6, 16, 2, 4, NOW(), NOW());

-- AHF sample data
INSERT INTO ahf_player_career (player_name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim, created_at, updated_at)
VALUES ('John Smith', '2023-2024', 'Alberta Youth Hockey', 'Calgary U17', 9, 30, 14, 9, 23, 3, 6, NOW(), NOW());
```

### Prerequisite: Link Parent to Player for Parent Access Testing

```sql
-- assuming player_id and parent_user_id exist
INSERT INTO account_link (parent_account_id, child_account_id, linked_at, verified)
VALUES ('{parent_user_id}', '{player_user_id}', NOW(), TRUE);
```

---

## Success Metrics

Upon completion, the feature will deliver:

| Metric | Target | Validation |
|--------|--------|------------|
| Career records visible in Seasons tab | All sources (AYHL, THF, AHF) | Q1: Player views own career (US1 test) |
| Parent can view child seasons | Yes, via existing account link | Q4: Parent views child profile → Seasons tab (US2 test) |
| Seasons endpoint response time | < 1 second p95 | Performance test (T036) |
| Authorization checks | 401 for non-authorized users | Integration test (T013, T020) |
| HTTP caching | Cache-Control header present | Response header validation (T009) |
| Test coverage | > 80% | `mvn jacoco:report` and `npm run test -- --coverage` |
| Deprecated code removed | 0 references to identity_map | Code review (T026) |
| Documentation updated | API docs + README | Docs in place (T034, T035) |

---

## Related Documents

- **Specification**: [spec.md](spec.md)
- **Technical Plan**: [plan.md](plan.md)
- **Data Model**: [data-model.md](data-model.md)
- **API Contracts**: [contracts/api-contracts.md](contracts/api-contracts.md)
- **Quickstart Guide**: [quickstart.md](quickstart.md)
- **Research & Decisions**: [research.md](research.md)

---

## Questions & Clarifications

If you encounter ambiguity during implementation, refer to:
1. **Clarification log** in spec.md (Session 2026-03-31)
2. **Design decisions** in research.md
3. **Code examples** in quickstart.md

For new questions, open an issue in GitHub with label `002-integrate-player-data` and reference the relevant spec section.

