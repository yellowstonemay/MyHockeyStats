-- Remove integration tables that are no longer used by runtime or ingestion flows.
-- Keep integration_import_run.

DROP TABLE IF EXISTS integration_imported_game_record;
DROP TABLE IF EXISTS integration_source_conflict;
