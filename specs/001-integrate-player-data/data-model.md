# Data Model: Integrate Player Data Sources

## Entity: UserAccountIdentity
- Purpose: Authenticated identity used for lookup and access checks.
- Fields:
  - `user_id` (Long, PK/FK to `users.id`)
  - `full_name` (string, required)
  - `birth_month` (integer 1-12, required)
  - `birth_year` (integer, required)
  - `role` (enum: `PLAYER`, `PARENT`, `ADMIN`)
  - `linked_player_user_id` (Long, nullable; for parent linkage)
- Validation:
  - `full_name` non-empty after normalization.
  - `birth_month` and `birth_year` required for matching flows.

## Entity: ImportRun
- Purpose: Auditable summary of each source import execution.
- Fields:
  - `id` (UUID, PK)
  - `source` (enum: `THF`, `AYHL`, `GAMESHEET`)
  - `trigger_type` (enum: `SCHEDULED_DAILY`, `OPERATOR_MANUAL`)
  - `status` (enum: `RUNNING`, `COMPLETED`, `FAILED`, `PARTIAL`)
  - `started_at` (timestamp)
  - `ended_at` (timestamp, nullable)
  - `processed_count` (int)
  - `accepted_count` (int)
  - `rejected_count` (int)
  - `duplicate_skipped_count` (int)
  - `error_summary` (text, nullable)
- Validation:
  - Counts must be non-negative.
  - `ended_at` required when status is terminal.

## Entity: ImportedPlayerRecord
- Purpose: Source-specific player identity and season context.
- Fields:
  - `id` (UUID, PK)
  - `source` (enum)
  - `source_player_id` (string, required where available)
  - `source_team_id` (string, nullable)
  - `source_club_id` (string, nullable)
  - `player_name_raw` (string, required)
  - `player_name_normalized` (string, required, indexed)
  - `birth_month` (integer, required)
  - `birth_year` (integer, required)
  - `season_label` (string, example `2024-2025`)
  - `import_run_id` (UUID, FK -> `ImportRun.id`)
  - `source_hash` (string; deterministic hash for idempotency)
  - `created_at` (timestamp)
- Validation:
  - Unique key for dedupe: (`source`, `source_hash`) or (`source`, `source_player_id`, `season_label`) when ID exists.
  - `birth_month` and `birth_year` required for candidate eligibility.

## Entity: ImportedGameRecord
- Purpose: Source-attributed game-level facts for a player.
- Fields:
  - `id` (UUID, PK)
  - `imported_player_record_id` (UUID, FK)
  - `source_game_id` (string, nullable)
  - `game_date` (date, nullable)
  - `opponent_name` (string, nullable)
  - `final_score_text` (string, nullable)
  - `goals` (integer, nullable)
  - `assists` (integer, nullable)
  - `points` (integer, nullable)
  - `import_run_id` (UUID, FK)
  - `logical_game_key` (string, indexed; used for cross-source conflict grouping)
- Validation:
  - Per-source dedupe key includes (`source`, `source_game_id`) when present, otherwise (`source`, `logical_game_key`, `imported_player_record_id`).

## Entity: MatchLink
- Purpose: Persistent association between account identity and imported player records.
- Fields:
  - `id` (UUID, PK)
  - `user_id` (Long, FK -> `users.id`)
  - `imported_player_record_id` (UUID, FK)
  - `link_state` (enum: `PENDING_SELECTION`, `CONFIRMED`, `REVERIFY_REQUIRED`, `UNLINKED`)
  - `match_method` (enum: `EXACT_NORMALIZED`, `FUZZY_CONFIRMED`)
  - `confidence_score` (decimal(5,4), nullable)
  - `confirmed_at` (timestamp, nullable)
  - `last_verified_at` (timestamp, nullable)
  - `identity_fingerprint` (string; signature of source identity signals)
  - `invalidated_reason` (string, nullable)
- Validation:
  - One active (`CONFIRMED`/`REVERIFY_REQUIRED`) link per (`user_id`, `source`) to avoid duplicate active links per source.

## Entity: SourceConflict
- Purpose: Track logical-stat disagreements across sources.
- Fields:
  - `id` (UUID, PK)
  - `user_id` (Long, FK)
  - `logical_record_key` (string, indexed)
  - `conflict_type` (enum: `GAME_STAT`, `TEAM_ASSIGNMENT`, `SCORE`, `OTHER`)
  - `field_name` (string)
  - `source_values_json` (jsonb; map of source -> value)
  - `is_active` (boolean)
  - `detected_at` (timestamp)
  - `resolved_at` (timestamp, nullable)
- Validation:
  - `source_values_json` must contain at least two distinct source entries.

## Relationships
- `ImportRun` 1:N `ImportedPlayerRecord`
- `ImportedPlayerRecord` 1:N `ImportedGameRecord`
- `UserAccountIdentity` 1:N `MatchLink`
- `MatchLink` N:1 `ImportedPlayerRecord`
- `UserAccountIdentity` 1:N `SourceConflict`

## Match Link State Transitions
1. `PENDING_SELECTION` -> `CONFIRMED`
   - Trigger: User selects candidate from ambiguity list.
2. `CONFIRMED` -> `REVERIFY_REQUIRED`
   - Trigger: Source identity fingerprint changes materially.
3. `REVERIFY_REQUIRED` -> `CONFIRMED`
   - Trigger: User confirms same or replacement candidate.
4. `CONFIRMED` -> `UNLINKED`
   - Trigger: User/admin unlink action or account closure.

## Derived Read Models
- `UnifiedPlayerHistoryView`
  - Aggregates linked records by source and season.
  - Includes `missingSources[]` and `conflicts[]` collections for UI.
- `MatchCandidateView`
  - Candidate list with score/explanations for ambiguity resolution.
