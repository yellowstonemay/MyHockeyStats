# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

**Language/Version**: Java 17 (backend), JavaScript/React 18 + Vite (frontend)
**Primary Dependencies**: Spring Boot 3.1.4, Spring Data JPA, Spring Security, JJWT, OpenPDF; Vite, React, React Router
**Storage**: PostgreSQL (managed: Railway for production; Docker Compose for local dev)
**Testing**: JUnit + Testcontainers (backend), Jest + React Testing Library (frontend)
**Target Platform**: Linux containers (Docker); deploy to Railway (managed Postgres + Docker / buildpacks)
**Project Type**: Web application (frontend SPA + backend API)
**Performance Goals**: initial target 100 RPS per backend instance; API p95 < 500ms for profile/season queries; background PDF export within 30s for moderate seasons
**Constraints**: memory footprint per service targeted <512MB; prefer pure-Java PDF renderer to avoid native binaries on Railway; secure-by-default configuration (TLS required in prod)
**Scale/Scope**: MVP supports 10k active players, with ability to scale horizontally via stateless backend and managed Postgres

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

[Gates determined based on `.specify/memory/constitution.md`]

- Player-First: MUST provide usable increment (account creation + profile) — plan includes auth and profile first.
- Secure Authentication & Access Control: MUST be implemented (JWT + Spring Security). Implementation present for signup/login; request filter pending.
- Data Privacy & Minimization: Must collect minimal PII (name, birthdate, location). Plan restricts external lookups to name + birthyear.
- Test-First: ALL new features MUST include tests. Current codebase has scaffolded tests missing for auth/profile — this is a violation that will be resolved in Phase 1 (tests added before merge).
- Interoperability & Exportability: PDF export required; plan selects a Java-based renderer (OpenPDF) to comply with Railway constraints.
- Observability & Logging: Plan mandates structured logging and correlation IDs; instrumentation tasks are included in Phase 1.

**Violations / Notes**:
- Test-First: currently not satisfied for implemented auth endpoints — justification: initial scaffold committed; immediate next PR will add unit/integration tests (gate must be closed before merging into main).

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
# [REMOVE IF UNUSED] Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# [REMOVE IF UNUSED] Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# [REMOVE IF UNUSED] Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure: feature modules, UI flows, platform tests]
```

**Structure Decision**: [Document the selected structure and reference the real
directories captured above]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
