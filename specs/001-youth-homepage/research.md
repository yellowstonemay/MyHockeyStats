# research.md

## Decision: Technology stack
- Chosen: React 18 + Vite (frontend); Spring Boot 3.1.4 on Java 17 (backend); PostgreSQL (Railway)
- Rationale: matches developer preference, strong ecosystem, easy containerization and Railway support.
- Alternatives considered: Node.js/Express or Python backend — rejected to keep JVM server-side libraries (OpenPDF) and leverage existing Spring expertise.

## Decision: Authentication
- Chosen: JWT issued by backend using JJWT + Spring Security for sessionless API auth.
- Rationale: simple stateless APIs, integrates well with SPA and Railway deployment. Use short-lived access tokens + refresh tokens if needed.
- Alternatives: session cookies (harder with SPA), third-party providers (Auth0) — deferred.

## Decision: PDF export
- Chosen: OpenPDF (pure-Java library) for server-side PDF generation.
- Rationale: avoids native binaries (wkhtmltopdf) which complicate deployment on Railway/buildpacks and Docker. OpenPDF supports templated generation and is JVM-native.
- Alternatives: wkhtmltopdf (rich HTML rendering) — rejected due to native binary and deployment complexity; headless Chromium (heavy).

## Decision: Local dev & CI
- Chosen: Docker Compose for local dev (db + backend + frontend), Testcontainers for integration tests in CI.
- Rationale: replicates production services and allows reliable integration tests.

## Decision: Performance goals and constraints
- Target: 100 RPS per backend instance; API p95 < 500ms for profile/season reads.
- Memory: services targeted <512MB per instance to fit common Railway limits.

## Research Tasks Completed
- Confirmed OpenPDF compatibility with Java 17 and Spring Boot.
- Validated Railway supports container-based deploys and managed Postgres.
- Selected JJWT for lightweight JWT handling in Spring Boot.

## Open Items (resolved)
- Performance Sizing: set conservative targets above.
- PDF rendering choice: resolved to OpenPDF to avoid native binaries.

## Alternatives & Rationale Summary
- PDF: OpenPDF (chosen) vs wkhtmltopdf (rejected) vs headless Chromium (rejected)
- Auth: JWT (chosen) vs server sessions (rejected for SPA simplicity)

