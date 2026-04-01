# Tasks: Integrate Player Data Sources

**Input**: Design documents from `/specs/001-integrate-player-data/`  
**Prerequisites**: `plan.md` (required), `spec.md` (required), `research.md`, `data-model.md`, `contracts/api-contracts.md`, `quickstart.md`

**Tests**: No standalone test tasks are included because test-first/TDD was not explicitly requested in `spec.md`.

**Organization**: Tasks are grouped by user story so each story can be implemented and validated independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: User story mapping (`US1`, `US2`, `US3`)
- All task descriptions include explicit file paths

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize feature scaffolding and shared configuration.

- [x] T001 Add integration scheduler and source path configuration in `backend/src/main/resources/application.yml`
- [x] T002 Create integration package scaffolding with placeholder package docs in `backend/src/main/java/com/myhockeystats/integration/package-info.java`
- [x] T003 [P] Add frontend integration API client module in `frontend/web/src/lib/integrationsApi.js`
- [x] T004 [P] Add frontend integration types/constants module in `frontend/web/src/lib/integrationsModel.js`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core persistence and domain primitives that block all stories.

**CRITICAL**: Complete this phase before starting any user story tasks.

- [x] T005 Create database migration for import runs, imported records, match links, and conflicts in `backend/src/main/resources/db/migration/V20260312_01__integration_core_tables.sql`
- [x] T006 [P] Implement import/match domain entities in `backend/src/main/java/com/myhockeystats/model/integration/ImportDomainEntities.java`
- [x] T007 [P] Implement repositories for integration entities in `backend/src/main/java/com/myhockeystats/repository/integration/IntegrationRepositories.java`
- [x] T008 Implement normalization and birth-month-year identity utilities in `backend/src/main/java/com/myhockeystats/service/integration/IdentityNormalizationService.java`
- [x] T009 Implement fuzzy matching scorer constrained by exact birth month-year in `backend/src/main/java/com/myhockeystats/service/integration/FuzzyMatchService.java`
- [x] T010 Implement link lifecycle state service for `PENDING_SELECTION/CONFIRMED/REVERIFY_REQUIRED/UNLINKED` in `backend/src/main/java/com/myhockeystats/service/integration/MatchLinkLifecycleService.java`
- [x] T011 Implement source conflict detection/aggregation service in `backend/src/main/java/com/myhockeystats/service/integration/SourceConflictService.java`
- [x] T012 Implement integration authorization guard for player/parent/admin scoping in `backend/src/main/java/com/myhockeystats/security/IntegrationAccessGuard.java`

**Checkpoint**: Foundation ready. User stories can now be implemented.

---

## Phase 3: User Story 1 - Player Sees Linked Historical Data at Login (Priority: P1) 🎯 MVP

**Goal**: Enable login-time matching, candidate selection, persistent linking, and player unified history with source attribution/conflicts.

**Independent Test**: Import sample source data, log in as a player, complete candidate selection when ambiguous, and load unified season history with conflict markers and missing-source indicators.

### Implementation for User Story 1

- [x] T013 [P] [US1] Implement source file parsers and mappers for THF/AYHL/GameSheet in `backend/src/main/java/com/myhockeystats/service/integration/SourceParserService.java`
- [x] T014 [US1] Implement idempotent import orchestration with per-source summaries in `backend/src/main/java/com/myhockeystats/service/integration/ImportRunService.java`
- [x] T015 [US1] Implement login-time match status service (linked/ambiguous/no-match) in `backend/src/main/java/com/myhockeystats/service/integration/MatchStatusService.java`
- [x] T016 [US1] Implement candidate confirmation workflow with persistent link updates in `backend/src/main/java/com/myhockeystats/service/integration/MatchConfirmationService.java`
- [x] T017 [US1] Implement unified player history read model (sources, missingSources, games, conflicts) in `backend/src/main/java/com/myhockeystats/service/integration/UnifiedHistoryService.java`
- [x] T018 [US1] Add authenticated player integration endpoints (`/me/match-status`, `/me/matches/confirm`, `/me/history`) in `backend/src/main/java/com/myhockeystats/api/IntegrationPlayerController.java`
- [x] T019 [US1] Add DTOs for match status, candidate selection, and unified history in `backend/src/main/java/com/myhockeystats/api/dto/integration/IntegrationDtos.java`
- [x] T020 [US1] Add player-facing ambiguity selection component in `frontend/web/src/components/integrations/MatchCandidateSelector.jsx`
- [x] T021 [US1] Add player-facing conflict and source-attribution display component in `frontend/web/src/components/integrations/SourceConflictPanel.jsx`
- [x] T022 [US1] Add player integration history page wired to new endpoints in `frontend/web/src/pages/IntegrationHistoryPage.jsx`
- [x] T023 [US1] Integrate history route and page entry in `frontend/web/src/App.jsx`
- [x] T024 [US1] Add no-match and missing-source UX messaging in `frontend/web/src/components/integrations/IntegrationStatusBanner.jsx`

**Checkpoint**: US1 is independently functional and deliverable as MVP.

---

## Phase 4: User Story 2 - Parent Views Child Data Through Linked Access (Priority: P2)

**Goal**: Allow linked parent accounts to view child unified history read-only with the same attribution/conflict behavior.

**Independent Test**: Log in as a linked parent, open child history view, and confirm read-only access with correct authorization boundaries.

### Implementation for User Story 2

- [x] T025 [US2] Implement parent-scoped history service wrapper using linked player identity in `backend/src/main/java/com/myhockeystats/service/integration/ParentHistoryService.java`
- [x] T026 [US2] Add parent read-only history endpoint (`/players/{playerUserId}/history`) in `backend/src/main/java/com/myhockeystats/api/IntegrationParentController.java`
- [x] T027 [US2] Enforce parent-link authorization checks for child history requests in `backend/src/main/java/com/myhockeystats/security/IntegrationAccessGuard.java`
- [x] T028 [US2] Add parent child-history page and data loader in `frontend/web/src/pages/ParentPlayerHistoryPage.jsx`
- [x] T029 [US2] Add read-only parent history route wiring in `frontend/web/src/App.jsx`

**Checkpoint**: US2 works independently without changing US1 player flows.

---

## Phase 5: User Story 3 - Admin/Operator Loads New Data Drops (Priority: P3)

**Goal**: Provide operator-triggered and daily scheduled ingestion visibility with per-source run summaries.

**Independent Test**: Trigger a manual import, inspect run status, and confirm scheduled daily run records are queryable per source.

### Implementation for User Story 3

- [x] T030 [US3] Implement operator import trigger endpoint (`POST /imports/run`) in `backend/src/main/java/com/myhockeystats/api/IntegrationImportController.java`
- [x] T031 [US3] Implement import run summary endpoint (`GET /imports/{runId}`) in `backend/src/main/java/com/myhockeystats/api/IntegrationImportController.java`
- [x] T032 [US3] Implement latest scheduled daily status endpoint (`GET /imports/daily/latest`) in `backend/src/main/java/com/myhockeystats/api/IntegrationImportController.java`
- [x] T033 [US3] Implement daily scheduled import runner with per-source isolation in `backend/src/main/java/com/myhockeystats/service/integration/DailyImportScheduler.java`
- [x] T034 [US3] Add operator import dashboard panel for run status and per-source counts in `frontend/web/src/components/integrations/ImportRunSummaryPanel.jsx`
- [x] T035 [US3] Add operator integration management page with manual trigger UI in `frontend/web/src/pages/IntegrationAdminPage.jsx`
- [x] T036 [US3] Add admin route and role-gated navigation entry in `frontend/web/src/App.jsx`

**Checkpoint**: US3 delivers independent operator ingestion management and daily import visibility.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final documentation, contract alignment, and operational hardening across all stories.

- [x] T037 [P] Update API contract details to match implemented request/response fields in `specs/001-integrate-player-data/contracts/api-contracts.md`
- [x] T038 [P] Update quickstart verification flow to match final endpoints and UI paths in `specs/001-integrate-player-data/quickstart.md`
- [x] T039 Add integration observability logging and correlation IDs in `backend/src/main/java/com/myhockeystats/service/integration/IntegrationAuditLogService.java`
- [x] T040 Validate and document daily-import operational runbook in `MANUAL_SEASON_ENTRY.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies.
- **Phase 2 (Foundational)**: Depends on Phase 1 and blocks all user stories.
- **Phase 3 (US1)**: Depends on Phase 2.
- **Phase 4 (US2)**: Depends on Phase 2; also uses US1 unified history contracts.
- **Phase 5 (US3)**: Depends on Phase 2; can proceed in parallel with US2 after foundation.
- **Phase 6 (Polish)**: Depends on completion of selected stories.

### User Story Dependency Order

- **US1 (P1)**: First delivery target (MVP).
- **US2 (P2)**: Depends on foundational auth/link checks and shared unified history model.
- **US3 (P3)**: Depends on import orchestration foundation and can be developed after US1 core ingestion services are stable.

### Within-Story Ordering

- Backend service/core tasks before API endpoint tasks.
- API endpoint tasks before frontend integration/view tasks.
- Route wiring after page/component implementation.

---

## Parallel Opportunities

- **Setup**: `T003` and `T004` can run in parallel.
- **Foundational**: `T006` and `T007` can run in parallel after `T005`.
- **US1**: `T013` can run in parallel with early frontend tasks (`T020`, `T021`) once DTO contracts are agreed.
- **US2 vs US3**: Full phases can run in parallel after `T024` if team capacity allows.
- **Polish**: `T037` and `T038` can run in parallel.

## Parallel Example: User Story 1

```bash
# Parallel backend/frontend kickoff after foundational services exist:
Task: T013 [US1] backend source parsers in backend/src/main/java/com/myhockeystats/service/integration/SourceParserService.java
Task: T020 [US1] frontend candidate selector in frontend/web/src/components/integrations/MatchCandidateSelector.jsx
Task: T021 [US1] frontend conflict panel in frontend/web/src/components/integrations/SourceConflictPanel.jsx
```

## Parallel Example: Post-MVP Story Development

```bash
# Parallelize US2 and US3 after US1 checkpoint:
Task: T025 [US2] parent history service in backend/src/main/java/com/myhockeystats/service/integration/ParentHistoryService.java
Task: T030 [US3] operator import trigger endpoint in backend/src/main/java/com/myhockeystats/api/IntegrationImportController.java
```

## Implementation Strategy

### MVP First (US1 only)

1. Complete Phase 1 (Setup).
2. Complete Phase 2 (Foundational).
3. Complete Phase 3 (US1).
4. Validate login-time matching, ambiguity selection, persistent links, and conflict visibility.
5. Demo/deploy MVP increment.

### Incremental Delivery

1. Deliver US1 for player value immediately.
2. Add US2 parent read-only visibility without changing player flow semantics.
3. Add US3 operator daily/manual import management.
4. Apply Phase 6 polish for docs and operations.

### Format Validation

- All tasks follow `- [ ] T### [P?] [US?] Description with file path`.
- Story labels are present only in user story phases.
- Setup/foundational/polish phases have no story label.
- Every task includes an explicit file path.
