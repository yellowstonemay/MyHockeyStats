# Implementation Plan: Integrate Player Data Sources

**Branch**: `001-integrate-player-data` | **Date**: 2026-03-12 | **Spec**: `specs/001-integrate-player-data/spec.md`
**Input**: Feature specification from `specs/001-integrate-player-data/spec.md`

## Summary

Implement a cross-source player data integration pipeline that ingests THF, AYHL, and GameSheet dumps into PostgreSQL, performs login-time identity lookup using fuzzy name plus exact birth month-year, and returns unified source-attributed results for players and linked parents. Ambiguous matches require user selection, selected links persist for future logins unless source identity signals change, conflicts are displayed side-by-side with source attribution, and ingestion runs as a daily batch with per-source reporting.

## Technical Context

**Language/Version**: Java 17 (Spring Boot backend), JavaScript (React 18 + Vite frontend), Python 3.x (existing data prep scripts)  
**Primary Dependencies**: Spring Boot Web/Data JPA/Security, PostgreSQL JDBC, JJWT, React Router, react-hook-form, zod  
**Storage**: PostgreSQL (`spring.datasource` in `backend/src/main/resources/application.yml`) for normalized source imports, match links, and import run summaries  
**Testing**: JUnit 5 + Spring Boot Test for unit/integration; MockMvc for API contracts; React component/integration tests (Vitest + React Testing Library) for ambiguity/conflict UI; import parser tests against sample CSV fixtures  
**Target Platform**: Dockerized Linux services in local compose and production-like container runtime; Windows-friendly local scripting  
**Project Type**: Full-stack web application with separate backend API and frontend SPA  
**Performance Goals**: Login-time matched dataset response <= 3s for p95 (aligned to SC-003); daily import success >= 95% per source over rolling 30 days (SC-006)  
**Constraints**: Must prevent cross-account exposure; fuzzy matching must be constrained by exact birth month-year; import reruns must be idempotent; preserve source-attributed conflicting values (no silent overwrite)  
**Scale/Scope**: Initial rollout for up to 10k accounts, daily batches per source, multi-season history, and game-level records from three upstream providers

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Phase 0 Gate Review

- Player-First (NON-NEGOTIABLE): PASS. Primary flows are login-time player/parent visibility and ambiguity resolution UX.
- Secure Authentication & Access Control: PASS. Existing auth is reused; new APIs remain authenticated and account-scoped.
- Data Privacy & Minimization: PASS. Matching uses name plus birth month-year only; source fields retained only for required history/provenance.
- Test-First (NON-NEGOTIABLE): PASS WITH ACTION. Plan mandates tests for matching logic, contracts, and import flows before implementation completion.
- Interoperability & Exportability: PASS. Imported provenance is modeled explicitly and contract-ready.
- Observability & Logging: PASS WITH ACTION. Import summaries and conflict flags are structured and auditable.
- Simplicity & Incremental Delivery: PASS. Work is decomposed into ingestion, matching/linking, and presentation slices.

## Project Structure

### Documentation (this feature)

```text
specs/001-integrate-player-data/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── api-contracts.md
└── tasks.md
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/myhockeystats/
│   ├── api/                      # existing controllers; add integration endpoints
│   ├── model/                    # existing entities; add import/match/conflict entities
│   ├── repository/               # existing repositories; add integration repositories
│   ├── service/                  # existing services; add import/matching/orchestration services
│   └── security/                 # existing auth/access controls
├── src/main/resources/
│   └── application.yml           # scheduling + datasource config
└── src/test/java/com/myhockeystats/   # add unit/integration/contract tests

frontend/web/
├── src/
│   ├── components/               # add ambiguity picker + conflict display components
│   ├── pages/                    # extend Dashboard/Profile for linked source data
│   ├── lib/                      # auth context and API client helpers
│   └── App.jsx                   # route-level integration state
└── package.json

scripts/
├── ayhl/                         # existing source extract scripts
├── gamesheet/                    # existing player/game stat extracts
└── thf-js/                       # existing THF/AHF extract scripts
```

**Structure Decision**: Web application structure (backend + frontend/web) with existing scripts as data-drop producers. New implementation centers in backend domain/service/api layers and frontend dashboard/profile views.

## Phase 0 Research Focus

- Identity matching strategy and thresholds for fuzzy name + exact birth month-year.
- Persistent link lifecycle and re-verification behavior when source identity changes.
- Conflict modeling and API shape for source-attributed values.
- Daily batch scheduling approach in Spring Boot with idempotent imports.

## Phase 1 Design Commitments

- Define normalized import entities, canonical keys, and dedupe constraints.
- Define match-link entity with status transitions (`PENDING_SELECTION`, `CONFIRMED`, `REVERIFY_REQUIRED`, `UNLINKED`).
- Define conflict representation contract for frontend rendering and export-safe lineage.
- Define authenticated endpoints for import status, candidate selection, linked data retrieval, and parent read-only access.

## Post-Phase 1 Constitution Re-Check

- Player-First: PASS. Ambiguous candidate selection and immediate data visibility are explicitly designed.
- Secure Access: PASS. Contracts are account-scoped and parent access remains delegated.
- Privacy: PASS. No additional sensitive identifiers beyond required matching and provenance.
- Test-First: PASS. Data-model and contracts include explicit tests for matching, conflicts, and permissions.
- Interoperability/Exportability: PASS. Source attribution is first-class in contracts and data model.
- Observability: PASS. Import run summaries and per-source errors are modeled and contract-visible.
- Simplicity: PASS. Chosen design avoids speculative merge-authority logic; only displays conflicts.

## Complexity Tracking

No constitution violations require justification.
