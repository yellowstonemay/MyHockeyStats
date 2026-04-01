# Research: Integrate Player Data Sources

## Decision: Identity matching rule
- Decision: Match candidates using exact birth month-year and normalized name matching that includes both exact and fuzzy comparisons.
- Rationale: This satisfies FR-004 and FR-015 while reducing false positives by requiring the exact birth month-year gate first.
- Alternatives considered: Full birthdate matching (rejected because requirement is month-year only); fuzzy-only name matching without birth constraint (rejected due to high collision risk).

## Decision: Ambiguous match handling
- Decision: If multiple candidates survive matching, return all candidates and require user selection before link creation.
- Rationale: Directly implements clarified requirement and FR-011 while preserving user trust for same-name collisions.
- Alternatives considered: Auto-pick highest fuzzy score (rejected due to potential identity mismatch); manual admin-only resolution (rejected because login flow must be self-service).

## Decision: Persistent link lifecycle
- Decision: Persist account-to-imported-player links and auto-reuse them on future logins unless source identity signals materially change.
- Rationale: Implements clarified requirement and FR-012/FR-013 with low-friction repeat logins.
- Alternatives considered: Re-prompt every login (rejected as poor UX); immutable links forever (rejected because source identity may drift or be corrected).

## Decision: Source identity change detection
- Decision: Trigger re-verification when key source identity signals diverge from prior confirmed link (for example: source player external ID changes, birth month-year mismatch, or large sustained name-distance increase).
- Rationale: Prevents stale or unsafe auto-linking while retaining persistence for stable identities.
- Alternatives considered: Time-based forced confirmation every N days (rejected because it creates unnecessary prompts); no re-checks (rejected due to safety risk).

## Decision: Conflict representation
- Decision: Store and return source-attributed values side-by-side with a conflict flag at logical record level.
- Rationale: Implements FR-014 and clarified requirement to show disagreements without silently choosing one source.
- Alternatives considered: Last-write-wins merge (rejected due to data loss and opacity); source priority override (rejected because priority is not product-defined).

## Decision: Import ingestion pattern
- Decision: Implement idempotent per-source batch imports with dedupe keys and run summaries, executed by daily scheduled jobs plus operator-triggered reruns.
- Rationale: Covers FR-001, FR-008, FR-009, FR-016 and supports both operational reliability and recovery.
- Alternatives considered: Login-time live scraping (rejected due to latency/reliability risks); event-stream ingestion (rejected as unnecessary complexity for current daily cadence).

## Decision: Scheduling mechanism
- Decision: Use Spring Boot scheduled tasks (cron in configuration) for daily imports and per-source status reporting persisted in import-run tables.
- Rationale: Fits existing backend stack and minimizes deployment complexity.
- Alternatives considered: External scheduler only (rejected for added operational coupling); OS cron invoking scripts directly (rejected due to weaker app-level observability).

## Decision: Implementation boundaries in this repository
- Decision: Keep source extraction scripts under `scripts/` as producers of drop files; backend owns normalization, persistence, matching, and API read models.
- Rationale: Reuses existing script assets while enforcing one authoritative ingestion and matching service in Java.
- Alternatives considered: Move all parsing into frontend (rejected for security and data-governance reasons); run Python loaders directly against DB in production (rejected to keep transactional and auth-safe backend boundary).

## Decision: Testing strategy
- Decision: Add backend unit tests for matching/conflict detection/import dedupe, integration tests for authenticated API and parent access rules, and frontend tests for ambiguity selection and conflict display states.
- Rationale: Satisfies constitution Test-First principle and functional risk profile.
- Alternatives considered: Manual validation only (rejected due to regression risk); backend-only testing (rejected because critical behavior appears in UI flows).
