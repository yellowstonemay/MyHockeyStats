# Quickstart: Integrate Player Data Sources

## Prerequisites
- Docker Desktop (or local PostgreSQL + Java 17 + Node 18+).
- Maven available for backend build.
- Existing source drop files available in `scripts/ayhl/data`, `scripts/thf-js/data`, and `scripts/gamesheet/data` (or configured ingest location).

## 1. Start services
From repo root:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

docker compose up --build
```

Expected local endpoints:
- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`

## 2. Prepare sample import data
- Ensure at least one player exists in each source dataset with matching birth month-year and near-matching names (for fuzzy test).
- Ensure one intentionally ambiguous case (same name + birth month-year with multiple players).
- Ensure one conflict case (same logical game with different goal/assist values by source).

## 3. Trigger imports
Use authenticated operator endpoint (contract defined in `contracts/api-contracts.md`) or scheduled runner.

Manual trigger sequence:
1. `POST /api/integrations/imports/run` with source list `THF`, `AYHL`, `GAMESHEET`.
2. Poll `GET /api/integrations/imports/{runId}` until terminal state.
3. Verify processed/accepted/rejected/duplicate counts per source.

## 4. Validate login-time matching
1. Sign in as player with known matching data.
2. If ambiguous, confirm candidate list appears and selection is required.
3. Confirm selected link persists and next login auto-links without prompt.
4. Modify source identity signal in test data, rerun import, then verify re-confirmation is required.

## 5. Validate parent read-only visibility
1. Sign in as linked parent account.
2. Open `http://localhost:5173/integrations/parent`.
3. Enter `playerUserId` and optional `linkedPlayerUserId` to verify parent-linked access path.
3. Confirm same source-attributed data and conflict flags are visible, read-only.

## 5a. Validate operator import dashboard
1. Sign in with operator/admin role.
2. Open `http://localhost:5173/integrations/admin`.
3. Run manual import and verify the run summary panel updates.
4. Load latest daily status and verify source-level statuses render.

## 6. Validate conflicts and missing sources
- Confirm API/UI returns:
  - `missingSources` when any source has no match.
  - conflict entries with per-source values (not overwritten).

## 7. Test checklist for implementation completion
Backend tests:
- Matching logic unit tests: exact, fuzzy, ambiguous, no-match.
- Match link lifecycle tests: persist, auto-reuse, reverify on identity change.
- Import idempotency tests: duplicate skip counts and stable records on reruns.
- Authorization tests: player scope + parent linked scope only.

Frontend tests:
- Candidate selection dialog flow.
- Conflict indicator and source-attributed value rendering.
- Missing source and no-match messaging.

## 8. Daily batch verification
- Configure scheduler cron (for example via application property `integration.import.daily.cron`).
- Run one forced scheduled cycle in non-prod.
- Confirm run summary records exist for each source and failures are isolated by source.
