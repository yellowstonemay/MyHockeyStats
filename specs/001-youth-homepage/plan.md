# Implementation Plan: Youth homepage and core player data services

**Branch**: `001-youth-homepage` | **Date**: 2026-02-23 | **Spec**: see project constitution and early design notes
**Input**: Feature specification drawn from the project constitution and subsequent user stories stored in research.md

This feature branch implements the initial public-facing homepage for the youth hockey stats platform along with the first tranche of backend services: authentication, player profile management, season & game data, and export infrastructure. The frontend uses a React/Vite SPA styled with Tailwind + shadcn/ui components. The backend is a Spring Boot REST API using JWT for auth and Postgres for storage. A Python/Playwright scraper will eventually feed league data into the system.

## Technical Context

**Language/Version**: Java 17 (backend), JavaScript/TypeScript (frontend), Python 3.x (scripts)  
**Primary Dependencies**: Spring Boot 3.1.4, Spring Security, JJWT, PostgreSQL JDBC driver, React 18, Vite, Tailwind CSS, shadcn/ui, Playwright (Python)  
**Storage**: PostgreSQL container (dev/CI) with named volume; data model described in data-model.md  
**Testing**: JUnit/Mockito/Spring Test + Testcontainers for backend; Jest/React Testing Library for frontend; Playwright for end‑to‑end tests; Python unit tests for scraper  
**Target Platform**: Linux containers (Docker Compose local dev, Railway production); occasional Windows during development  
**Project Type**: Full‑stack web application with separate frontend SPA and backend API; auxiliary Python scraper scripts  
**Performance Goals**: Backend should handle ≈100 requests per second per instance; API p95 latency <500 ms for common read endpoints (profile/season listings).  
**Constraints**: Memory per service <512 MB to fit common hosting SKU; p95 latency <500 ms; use containerizable, JVM‑only libraries (no native deps) for portability; adhere strictly to data minimization and secure auth policies.  
**Scale/Scope**: Target initial rollout to thousands of youth hockey players (<10 k user accounts) with room to grow; codebase currently ~5 k lines but expected to expand toward ~1 M LOC as features accumulate.

## Constitution Check

The constitution has several non‑negotiable principles. This feature complies with them as follows:

- **Player‑First**: Home page invites account creation; subsequent services (profiles, seasons, game history) deliver concrete player value early.  
- **Secure Authentication & Access Control**: JWT‑based login was implemented; passwords are hashed; CORS is restricted to localhost during development. Parent linkage is planned but not yet needed for MVP.  
- **Data Privacy & Minimization**: Only essential profile fields (name, birthdate, location) are stored; scraper uses minimal identifying information.  
- **Test‑First**: All new backend controllers and services include unit tests; frontend components have accompanying Jest tests.  
- **Interoperability & Exportability**: API contract covers export endpoint; groundwork for external league lookup service exists.  
- **Observability & Logging**: Basic structured logging present in backend; correlation IDs added to controllers.  
- **Simplicity & Incremental Delivery**: The implementation avoids over‑engineering; more complex features (refresh tokens, advanced analytics) deferred to later features.

No gates are currently violated; hence research may proceed.

## Project Structure

### Documentation (this feature)

```text
specs/001-youth-homepage/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (future)
```

### Source Code (repository root)

```text
backend/                 # Spring Boot application
├── src/
│   ├── main/java/...    # models, controllers, services, security
│   └── test/java/...    # unit & integration tests
├── Dockerfile
└── pom.xml

frontend/                # React/Vite application
└── web/
    ├── src/
    │   ├── components/  # shared UI components
    │   ├── pages/       # route pages (Home, SignIn, SignUp, Dashboard)
    │   └── utils/       # API client, auth helpers
    ├── public/
    ├── package.json
    └── vite.config.js

scripts/                 # auxiliary Python utilities (db connection, scrapers)
```

**Structure Decision**: Option 2 (Web application) since the workspace contains distinct `backend` and `frontend/web` directories; auxiliary Python scripts live under `scripts/`.

## Complexity Tracking

No constitution violations or extra projects have been introduced; therefore, no complexity tracking table is necessary.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

[Gates determined based on constitution file]

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
