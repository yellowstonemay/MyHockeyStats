# quickstart.md

## Local quickstart (dev)

1. Build and run with Docker Compose (requires Docker):

- From repository root:

  docker compose up --build

2. Environment variables (for local development, see `docker-compose.yml`):
- `SPRING_DATASOURCE_URL` (default: postgres jdbc URL used by compose)
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `JWT_SECRET` (set a secure random value locally)

3. Access points
- Frontend: http://localhost:5173 (Vite)
- Backend API: http://localhost:8080/api

4. Running tests
- Backend unit/integration: run `./mvnw test` (Testcontainers will start a Postgres container if configured)
- Frontend tests: `npm test` inside `frontend/web` if configured

5. Notes
- PDF exports are generated server-side and saved to a downloads folder or object storage when configured.
- CI pipeline should run tests and build containers before deploy to Railway.
