# Implementation Plan: Youth Homepage & Dashboard

**Branch**: `001-youth-homepage` | **Date**: 2026-02-23 | **Spec**: (see `specs/001-youth-homepage/spec.md`)
**Input**: Feature specification from `specs/001-youth-homepage/spec.md`

## Summary

Build a web application (React frontend + Spring Boot backend, PostgreSQL) targeted for deployment to Railway. Deliver an MVP exposing: public homepage with Youth Ice Hockey Lego graphic, signup/signin, and after auth four main tabs: `Profile`, `Season & Team Summary`, `Game History`, and `Dashboard`. Focus on secure auth, player profile management, season/team lookups (external adapters), game history browsing, server-side PDF export, and lightweight dashboard charts.

## Technical Context

**Language/Version**: Frontend: React 18 (TypeScript recommended). Backend: Java 17 (Spring Boot 3.x).
**Primary Dependencies**: Frontend: React, React Router, React Query (or SWR), Charting (Recharts/Chart.js), Tailwind CSS or Chakra UI. Backend: Spring Boot, Spring Web, Spring Data JPA, Spring Security, Flyway (DB migrations), Jackson, Thymeleaf optional for server-side PDF templates, OpenPDF/Apache PDFBox or wkhtmltopdf integration for PDF rendering.
**Build Tools**: Frontend: npm/Yarn + Vite. Backend: Maven or Gradle (Maven recommended for Railway compatibility).
**Storage**: PostgreSQL (managed via Railway). Consider using Railway's provided DATABASE_URL; configure via Spring Boot `spring.datasource.url`.
**Background Jobs**: Lightweight job queue implemented via database-backed `ExportJob` table + Spring @Scheduled worker, or integrate Redis/RabbitMQ if Railway plan allows and scale requires it.
**Testing**: Frontend: Jest + React Testing Library. Backend: JUnit 5, Spring Boot Test, Testcontainers for Postgres during CI. Contract tests for HTTP APIs.
**Target Platform**: Modern web browsers; backend deploys to Railway (container or Java deploy), database on Railway Postgres.
**Project Type**: Monorepo with `frontend/web` and `backend/` directories.
**Performance Goals**: API p95 < 200ms for read endpoints under small scale; PDF export for up to 50 games completes within 30s (or is queued and status reported).
**Constraints**: Railway enforces build and startup semantics — prefer JVM options and shorter boot time; use environment variables for configuration. Use Test-First approach per constitution.

## Constitution Check

- Authentication & Authorization: Implement via `Spring Security` with secure password hashing (BCrypt) and JWT or secure session cookies. Parent linking and access must be auditable.
- Test-First: Write failing tests before implementation (unit + integration using Testcontainers).
- Data Privacy: Store only necessary PII (birthdate, location) and log external lookups without excess PII.

Status: This plan follows the constitution. Any deviations must be documented in feature `Constitution Check`.

## Project Structure

backend/
- src/main/java/ (Spring Boot app)
  - com.myhockeystats.app
    - Application.java
    - api/ (controllers)
    - service/
    - model/ (JPA entities)
    - repository/
    - security/
    - workers/
  - src/main/resources/
    - application.yml
    - db/migration/ (Flyway)
  - pom.xml

frontend/
- web/
  - src/
    - pages/
    - components/
    - services/ (api clients)
    - hooks/
    - styles/
  - package.json
  - vite.config.ts

docs/
- quickstart.md

Structure Decision: Monorepo keeps frontend and backend in one repository, simplifies CI and local docker-compose (Postgres). Build and deployment remain separate: frontend deployed as static assets or served by Railway static site; backend deployed as a Java service.

## Implementation Phases & Tasks (high level)

Phase 1 — Setup
- Initialize `frontend/web` (Vite + React + TypeScript) and `backend` (Spring Boot with Maven). Add linting/formatting configs.
- Add Dockerfiles and `docker-compose.yml` for local dev: Postgres + backend + frontend static server.
- Add CI workflow: build backend (mvn test), build frontend (npm test), run integration tests using Testcontainers.

Phase 2 — Foundational
- Implement authentication with Spring Security, `User` entity, password hashing (BCrypt), and JWT/session handling.
- Create JPA entities: `PlayerProfile`, `Season`, `Game`, `GameEvent`, `ExportJob` and Flyway migrations.
- Implement service layer and repositories; add integration tests using Testcontainers Postgres.
- Add background export worker: DB-backed job queue with a scheduled worker reading `ExportJob` rows and processing PDFs.
- Add structured logging and correlation IDs.

Phase 3 — MVP User Stories (P1 first)
- US1: Signup/Signin endpoints + frontend pages. Tests (contract + integration).
- US2: Player profile CRUD endpoints + frontend UI.
- US4: Game history APIs and frontend listing/detail views.

Phase 4 — Secondary Stories (P2)
- US3: Season & Team Summary with external league adapter (HTTP client service) and an admin/manual-confirm flow for partial matches.
- PDF Export: ExportJob flow, worker, status endpoints, and frontend polling.
- Dashboard: Aggregation endpoints and frontend charts.

Phase 5 — Polish & Deploy
- Accessibility, responsive UI, security review, performance tuning.
- Railway deployment: prepare `Dockerfile` for backend, set environment variables in Railway (e.g., `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`), and follow Railway build/deploy docs. For frontend, consider deploying static build via Railway static site or serve from backend.

## Quickstart (developer)

1. Start local services via Docker Compose:

```powershell
docker-compose up --build
```

2. Backend dev (Maven):

```powershell
cd backend
mvn clean package
mvn -DskipTests spring-boot:run
```

3. Frontend dev:

```bash
cd frontend/web
npm install
npm run dev
```

## Railway Deployment Notes

- Railway provides a `DATABASE_URL` style connection string. For Spring Boot, set `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<db>?sslmode=require` and `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` appropriately, or parse `DATABASE_URL` during startup.
- Configure `JAVA_TOOL_OPTIONS` or memory settings if Railway memory is constrained.
- Use Railway environment variables for `JWT_SECRET`, external API keys, and any third-party credentials. Keep secrets out of repo.
- For PDF generation requiring native binaries (wkhtmltopdf), prefer a Docker-based worker image including the binary and deploy it as a separate Railway service or container.

## Tests & CI

- Use Testcontainers for integration tests of repositories and Flyway migrations in CI.
- CI pipeline must run `mvn test` and `npm test` and fail on test failures.

## Next Steps / Clarifications

- Confirm build tool: Maven (recommended) or Gradle? (default: Maven)
- Confirm auth style: session cookies or JWTs? (recommend JWT for API-based SPA + Railway stateless services)
- Confirm PDF renderer choice: server-side Java library (OpenPDF) vs. wkhtmltopdf binary (better HTML fidelity). Use wkhtmltopdf in Docker if fidelity required.
