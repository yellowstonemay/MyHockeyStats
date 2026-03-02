# Phase 2 Tasks for `001-youth-homepage`

The following actionable tasks will be tracked and executed incrementally. Each task should be marked complete as soon as it is finished.

## Backend
1. **Define JPA entities and repositories** for PlayerProfile, Season, Game, GamePerformance matching data-model.md. (User entity and repository already exist.)
2. **Extend `User` model** with `role` enum [PLAYER, PARENT, ADMIN] and add necessary database migration.
3. **Authentication service is already implemented** with password hashing, JWT generation and a working `AuthController`. Review and add unit tests if missing.
4. **Create controllers and DTOs** for:
   - Player profile CRUD (`/api/players` endpoints) with service layer and tests
   - Season listing and game queries (`/api/players/{playerId}/seasons` etc.)
   - Export job submission and status endpoints; integrate OpenPDF stub
5. **Write integration tests** using Spring Boot test + Testcontainers covering profile, season and export flows.
6. **Enhance security configuration**: add JWT filter to secure player endpoints, refine CORS and add correlation ID filter.
7. **Add health check endpoint** (`/actuator/health`) already exists; ensure actuator is enabled in production profile.
8. **Build Docker image** via `backend/Dockerfile` and verify `docker compose` startup continues to work.

## Frontend
1. **Verify existing route structure**: home, sign‑up, sign‑in, dashboard, profile are already implemented. Add protected route wrappers and redirect logic as needed.
2. **Enhance UI components**:
   - Add navigation bar with dynamic login/logout state (currently missing)
   - Extend forms to include additional profile fields and validation
   - Implement season/game list views and export button once backend supports data
3. **API client utilities exist** in `lib/utils.js` with auth handling; review and add error parsing and refresh token support later.
4. **Auth state is persisted via `localStorage`**; consider migrating to React Context for easier access across components.
5. **Write and expand Jest tests** for existing pages (`SignUp`, `SignIn`, etc.) and new components.
6. **Continue building and running Vite**; check preview at `http://localhost` after backend changes.

## Scraper & Data Ingestion
1. **Refactor `scripts/scrape_league.py`** by extracting page‑parsing logic into reusable functions that return structured dicts; maintain CLI output as JSON (already implemented).
2. **Add Python unit tests** for parsing logic using saved HTML snippets to ensure robustness against layout changes.
3. **Integrate with database**: use `scripts/connect_db.py` to insert or update PlayerProfile, Season, and Team data; implement idempotency.
4. **Schedule or orchestrate scraper runs**: plan a backend job or simple cron/powershell script; mock scheduler during development.

## Testing & CI
1. **Add GitHub Actions workflow** to build backend, run tests, build frontend, run Jest tests, and lint.
2. **Include Python lint/tests** for scraper stage.
3. **Set up Testcontainers for CI** (already depends on environment variable). Ensure `docker` is available.

## Documentation & Cleanup
1. **Update `quickstart.md`** with any new commands or environment variables.
2. **Document PDF export schema** and example output in a separate docs folder.
3. **Review and refactor code** for simplicity as needed.

---

Tasks should be managed via the workspace TODO list or GitHub issues. Prioritize backend auth and profile flows first, then frontend integration, and finally scraping and CI.
