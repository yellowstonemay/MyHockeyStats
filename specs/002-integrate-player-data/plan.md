# Implementation Plan: Simplified Player Data Integration (Redesign)

**Branch**: `002-integrate-player-data` | **Date**: 2026-03-31 | **Spec**: [spec.md](spec.md)  
**Input**: Feature specification from `/specs/002-integrate-player-data/spec.md`

## Summary

**Primary Requirement**: Redesign the player career data integration feature to eliminate the persistent "identity map" linking and confirmation workflow, replacing it with a simple runtime-lookup "Seasons" tab that queries AYHL, THF, and AHF career tables by normalized player name and displays all matching records transparently.

**Technical Approach**: 
- Backend: New `CareerLookupService` implements exact normalized name matching across three career tables; single `GET /api/players/{playerId}/seasons` endpoint returns union of all records with source attribution.
- Frontend: New "Seasons" tab component replaces old "Integrated History" page; displays all records as separate rows per source.
- Database: Add functional indexes on normalized name (no schema changes to career tables); deprecate `player_identity_map` table.
- Authorization: Extend existing `IntegrationAccessGuard` to check parent links; use stateless runtime authorization (no persistent linking).

**Value**: Immediate, frictionless access to player history without confirmation steps; transparent display of all sources allows players to verify data and spot discrepancies.

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x), React 18 + Vite, PostgreSQL 13+

**Primary Dependencies**: 
- Backend: Spring Boot, Spring Data JPA, Lombok, JUnit 5
- Frontend: React, React Router, Tailwind CSS, axios/fetch

**Storage**: PostgreSQL with existing career tables: `ayhl_player_career`, `thf_player_career`, `ahf_player_career`, plus related account/player/user tables

**Testing**: 
- Backend: JUnit 5 + MockMvc for REST, mocking for repositories
- Frontend: Vitest/Jest for unit tests, React Testing Library for component tests

**Target Platform**: Web (any modern browser); deployed on cloud infrastructure

**Project Type**: Web service (Spring Boot REST API + React SPA)

**Performance Goals**: 
- Career lookup response: < 1 second (p95)
- Browser cache hit rate: > 70% (5-min TTL)
- Zero auth bypass incidents

**Constraints**: 
- < 5-minute data freshness acceptable (career data doesn't change frequently)
- Name-based lookup only (exact normalized match; no fuzzy matching)
- No new persistent linking table required (use existing account link auth)

**Scale/Scope**: 
- ~500 youth hockey players, ~20-30 seasons per player
- 3 sources × ~500 players × 25 seasons = ~37,500 career records total (manageable)
- Single page load queries: O(n) where n = matching records for one player (typically 0-20)

## Constitution Check

**GATE: All checks passed. No deviations.**

✅ **Player-First**: Feature directly improves player experience (no confirmation friction; transparent data).  
✅ **Secure Authentication & Access Control**: Authorization enforced via `IntegrationAccessGuard` (player + linked parents only).  
✅ **Data Privacy & Minimization**: Lookup uses only player name (no extra PII collection); returns only career stats player is authorized to view.  
✅ **Test-First**: Unit and integration tests defined in quickstart.md; contract tests for API.  
✅ **Interoperability & Exportability**: Career data remains in queryable career tables; can export via existing PDF export flow.  
✅ **Observability & Logging**: Endpoint logs query execution time, cache hits, ambiguity detection, authorization checks.  
✅ **Simplicity & Incremental Delivery**: Direct lookup is simpler than identity map; stateless; deployable as single feature without dependencies.  

**No violations. Feature aligns with constitution.**

## Project Structure

### Documentation (this feature)

```text
specs/002-integrate-player-data/
├── plan.md                 # This file (Phase 1 output)
├── research.md             # Phase 0 output (resolved clarifications)
├── data-model.md           # Phase 1 output (career entities, lookup patterns)
├── quickstart.md           # Phase 1 output (implementation guide)
├── spec.md                 # Input feature specification
├── tasks.md                # Phase 2 output (NOT created by plan)
├── checklists/
│   └── requirements.md     # Requirement verification checklist
└── contracts/
    └── api-contracts.md    # Phase 1 output (REST endpoint contracts)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/myhockeystats/
│   ├── api/
│   │   └── SeasonsController.java              # NEW: Seasons endpoint
│   ├── service/integration/
│   │   └── CareerLookupService.java            # NEW: Career lookup logic
│   ├── repository/
│   │   ├── AyhlPlayerCareerRepository.java     # EXTEND: add findByNormalizedName
│   │   ├── ThfPlayerCareerRepository.java      # EXTEND: add findByNormalizedName
│   │   └── AhfPlayerCareerRepository.java      # EXTEND: add findByNormalizedName
│   ├── api/dto/integration/
│   │   ├── SeasonCareerRecordDto.java          # NEW: unified season record DTO
│   │   └── SeasonsResponseDto.java             # NEW: response wrapper
│   └── security/
│       └── IntegrationAccessGuard.java         # EXTEND: add canViewSeasons()
└── src/main/resources/db/migration/
    └── V[X]__add_career_lookup_indexes.sql     # NEW: functional indexes

frontend/web/
├── src/
│   ├── components/PlayerProfile/
│   │   └── SeasonsTab.jsx                      # NEW: unified seasons display
│   ├── lib/
│   │   └── api.js                              # EXTEND: add fetchPlayerSeasons()
│   └── pages/
│       └── PlayerProfile.jsx                   # MODIFY: add Seasons tab
└── src/components/
    └── MatchConfirmation/                      # REMOVE DIRECTORY: deprecated flow
```

**Structure Decision**: 
- Follows existing Spring Boot package structure (already established: `com.myhockeystats.service`, `.api`, `.repository`).
- Frontend follows React component hierarchy (pages/ for routes, components/ for reusables).
- New code co-located with existing integration code (IntegrationAccessGuard, IntegrationPlayerController).
- Cleanup is straightforward: remove old IntegrationPlayerController methods, deprecate player_identity_map table.

## Complexity Tracking

> **No violations. No complex trade-offs required.**

All requirements are met with minimal architectural extensions. The feature is simpler than the original design (no persistent linking, no confirmation workflow).

---

## Phase Overview

### Phase 0: Research (COMPLETE)

**Deliverable**: `research.md`  
**Status**: ✅ Complete

All clarifications resolved in spec session 2026-03-31:
- Name-based lookup (exact normalized match)
- Ambiguity handling (display all records with note)
- Parent access (existing account link auth)
- Caching (5-minute HTTP cache)
- Multi-source display (separate rows per source)

No further research required. Proceed to Phase 1.

---

### Phase 1: Design & Contracts (IN PROGRESS)

**Deliverables**: 
- ✅ `data-model.md` (Career entities, lookup patterns)
- ✅ `contracts/api-contracts.md` (REST endpoint specifications)
- ✅ `quickstart.md` (Implementation quick-start)
- ⏳ Agent context update (next step)

**Completion Criteria**:
- All DTOs defined
- All API contracts documented with examples
- Implementation quickstart provides concrete code examples
- Agent context updated with new technology/patterns

---

### Phase 2: Implementation (Outline Only)

**Estimated Duration**: 10-14 days  
**Team**: 1 backend developer + 1 frontend developer (parallel possible)

#### Phase 2a: Backend (7-8 days)

**Sprint 1** (Days 1-3):
- Create `CareerLookupService` with normalization logic
- Add repository methods `findByNormalizedName()` for each career table
- Create DTOs: `SeasonCareerRecordDto`, `SeasonsResponseDto`
- **Code Review**: Normalize logic correctness

**Sprint 2** (Days 4-5):
- Implement `SeasonsController.getPlayerSeasons()` endpoint
- Extend `IntegrationAccessGuard.canViewSeasons()`
- Set HTTP cache headers
- **Code Review**: Authorization checks, cache headers, error handling

**Sprint 3** (Days 6-7):
- Add database migration: functional indexes on normalized name
- Add unit tests: CareerLookupService, SeasonsController
- Add integration tests: end-to-end lookup flow
- **Code Review**: Test coverage > 80%

**Sprint 4** (Days 8):
- Clean up: Remove old IntegrationPlayerController methods
- Document deprecation: player_identity_map table
- Merge to main branch

#### Phase 2b: Frontend (1-3 days, in parallel)

**Sprint 1** (Days 1-2):
- Create `SeasonsTab.jsx` component
- Add API client function: `fetchPlayerSeasons()`
- Integrate tab into `PlayerProfile.jsx`
- **Code Review**: Component rendering, data binding

**Sprint 2** (Days 3):
- Add error handling and retry logic
- Add unit tests: SeasonsTab component, API client
- Remove old "Integrated History" page + route
- **Code Review**: Error scenarios, UX

#### Phase 2c: Testing & QA (3-4 days, parallel or sequential)

- Manual testing: All acceptance scenarios from spec
- Browser caching verification
- Authorization bypass testing (parent access)
- Load testing: 100+ concurrent lookups (verify performance)
- **Pass/Fail Criteria**: All acceptance scenarios pass + performance targets met

#### Phase 2d: Deployment Readiness (1-2 days)

- Generate migration scripts for production
- Create rollback procedure
- Document operational runbook
- Deploy to staging environment
- Smoke tests on staging

---

### Phase 3: Deployment & Monitoring (Outline Only)

**Estimated Duration**: 1-2 days

- Deploy to production (or staged rollout 10% → 50% → 100%)
- Monitor error logs and performance metrics
- Verify cache hit rates, response times
- Watch for auth bypass attempts
- Collect user feedback

**Success Metrics**:
- ✅ 95%+ of requests complete in < 1 sec
- ✅ 0 authorization bypass incidents
- ✅ > 70% cache hit rate
- ✅ Positive player feedback

---

## Dependencies & Risk Mitigation

### External Dependencies

**None** - Feature uses only existing infrastructure:
- Existing career tables (no schema changes)
- Existing account link auth (no new mechanism)
- Existing Spring Boot/React stack
- Existing database (PostgreSQL)

**Risk**: If career table schemas vary by source, normalization may miss records. **Mitigation**: Document schema assumptions; verify in Phase 2a code review.

### Internal Dependencies

1. **Player Profile Service** (existing): Used to fetch player name for lookup. ✅ Already stable.
2. **IntegrationAccessGuard** (existing): Used for authorization. ✅ Already stable.
3. **Account Link Repository** (existing): Used to verify parent-child links. ✅ Already stable.

**Risk**: If any service changes, could break Seasons endpoint. **Mitigation**: Add integration tests; notify service owners.

### Data Dependencies

**Career Tables** must be populated with recent data for feature to add value.

**Risk**: If tables are stale, players see old data and may assume feature is broken. **Mitigation**: Verify import process is running before launch; document data freshness SLA (e.g., "within 24 hours of source update").

---

## Deployment Strategy

### Pre-Deployment Checklist

- [ ] Code reviewed and approved (backend + frontend)
- [ ] Unit tests pass (coverage > 80%)
- [ ] Integration tests pass (all acceptance scenarios)
- [ ] Functional indexes created on career tables
- [ ] Database migration tested on staging
- [ ] Rollback procedure documented
- [ ] Monitoring dashboards created (response time, error rate, cache hit rate)
- [ ] Runbook written (how to troubleshoot)

### Deployment Steps

**Step 1** (Staging Env):
1. Apply database migration (add indexes)
2. Deploy backend code (SeasonsController, CareerLookupService)
3. Deploy frontend code (SeasonsTab component)
4. Smoke test: Verify Seasons tab loads + displays career data

**Step 2** (Production - Staged Rollout):
1. Deploy to 10% of users (canary)
2. Monitor for 1 hour (error rate, response time)
3. If OK, deploy to 50% of users
4. Monitor for 2 hours
5. If OK, deploy to 100% of users
6. Monitor for 24 hours

**Step 3** (Post-Deployment):
1. Collect user feedback (does it work? any issues?)
2. Verify cache hit rate > 70%
3. Verify response time < 1 sec (p95)
4. Confirm no auth bypass issues
5. Close feature ticket

### Rollback Steps (if needed)

**Immediate**:
1. Revert frontend code (old "Integrated History" page still available)
2. Revert backend code (old IntegrationPlayerController methods still live)
3. Verify players can still access career data via old means

**Note**: Do NOT drop database indexes or player_identity_map table; keep for 1 week after rollback is confirmed unnecessary.

---

## Communication & Documentation

### Before Launch

- [ ] Notify stakeholders (product, QA, support) of launch date
- [ ] Share quickstart guide with dev team
- [ ] Prepare user-facing docs (if any): "View Your Career History"
- [ ] Brief support team on new feature (what changed, how it works)

### Launch Day (Release Notes)

```markdown
## Feature: Unified Career History (Seasons Tab)

**What's New**: Players can now see their complete career history from 
all leagues (AYHL, THF, AHF) in one "Seasons" tab without confirmation steps.

**How It Works**: 
1. Click "Seasons" tab in your player profile
2. View all available seasons and teams across all sources
3. Each season shows full stats (GP, G, A, Pts, PIM)
4. Source label (AYHL/THF/AHF) helps identify which league provided the data

**What Changed**:
- Removed "Integrated History" page (old flow)
- Removed confirmation step (now displays all records automatically)
- Added direct career lookup (faster, more transparent)

**Questions?** Contact support@myhockeystats.com
```

### Post-Launch

- [ ] Monitor feedback channels (GitHub issues, user reports)
- [ ] Track performance metrics weekly
- [ ] Document any issues found and fixes applied
- [ ] Plan v2.0 enhancements (filtering, export, etc.)

---

## Success Criteria & KPIs

### Functional Correctness

- ✅ Players can view all career records from all 3 sources in Seasons tab
- ✅ Parents can view linked child's career records
- ✅ Ambiguous matches (multiple players with same name) display all records + warning
- ✅ No career records found displays friendly message + explanation
- ✅ Database errors display user-friendly retry message
- ✅ Authorization checks prevent unauthorized data access

### Performance

- ✅ Career lookup response time: < 1 second (p95)
- ✅ Browser cache hit rate: > 70% (same user, 5-min window)
- ✅ No database timeouts: < 0.1% of requests (< 30-sec timeout)
- ✅ Index lookup time: < 100ms per source query

### Implementation Quality

- ✅ Code coverage: > 80% (unit + integration tests)
- ✅ 0 security incidents (auth bypass, data leaks)
- ✅ 0 breaking changes to existing APIs (old endpoints still work during Phase 3)
- ✅ Documentation complete (quickstart, API contracts, runbook)

### User/Business Value

- ✅ Player feedback: "I can now see all my hockey history in one place"
- ✅ Time to view career data: < 2 seconds (from login to Seasons tab loaded)
- ✅ Support ticket volume for "How do I see my old stats?" decreases by 50%+

---

## Open Questions & Unknowns: NONE

All technical decisions have been resolved in Phase 0 research. 

**Refer to**: 
- `research.md` for decision rationale and alternatives considered.
- `spec.md` Session 2026-03-31 for original clarifications.

---

## Assumptions Validated

✅ Career tables (ayhl_player_career, thf_player_career, ahf_player_career) exist and are regularly populated.  
✅ Career table schemas include: name, season, club, team, jersey_number, games_played, goals, assists, points, penalties, pim.  
✅ PlayerProfile entity includes `full_name` field used for lookup.  
✅ AccountLink rows exist for parent-child relationships via existing platform auth.  
✅ HTTP caching is acceptable for 5-minute data freshness.  
✅ Spring Boot 3.x + React 18 stack is stable and available.  
✅ PostgreSQL supports functional indexes (`LOWER(TRIM(...))` in index definition).  

---

## Next Steps

### Immediate (Today)

- [x] Generate Phase 0 research.md
- [x] Generate Phase 1 artifacts (data-model.md, api-contracts.md, quickstart.md)
- [x] Fill in plan.md
- [ ] **Run agent context update** (activate_notebook_package_management or similar)

### Week 1

- [ ] Code review: Phase 1 design artifacts (data-model, contracts, quickstart)
- [ ] Backend dev kickoff: Assign backend developer to Phase 2a tasks
- [ ] Frontend dev kickoff: Assign frontend developer to Phase 2b tasks
- [ ] QA coordination: Prepare test environment, acceptance test checklist

### Week 2-3

- [ ] Complete Phase 2 implementation sprints
- [ ] Code reviews + fixes
- [ ] Testing & QA validation
- [ ] Deploy to staging

### Week 4

- [ ] Production deployment (staged rollout)
- [ ] Monitoring & post-launch support
- [ ] Feature closeout

---

## References & Related Documents

- **Specification**: [spec.md](spec.md) - Feature requirements, user stories, acceptance scenarios
- **Data Model**: [data-model.md](data-model.md) - Career entities, DTOs, database lookups
- **API Contracts**: [contracts/api-contracts.md](contracts/api-contracts.md) - REST endpoint specs, error responses
- **Quickstart**: [quickstart.md](quickstart.md) - Implementation code examples, step-by-step guide
- **Research**: [research.md](research.md) - Design decisions, rationale, technologies used
- **Constitution**: [.specify/memory/constitution.md](.specify/memory/constitution.md) - Project principles & governance

---

## Sign-Off

This implementation plan is ready for Phase 2 development.

**Prepared by**: Implementation Planner  
**Date**: 2026-03-31  
**Status**: ✅ APPROVED FOR IMPLEMENTATION
