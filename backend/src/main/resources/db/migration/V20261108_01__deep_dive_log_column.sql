-- Keep the last deep-dive transcript per source link so the admin page can show
-- what the scrape actually did (the status alone said SUCCESS while the source
-- page had been replaced by a 404).
ALTER TABLE player_source_links
    ADD COLUMN IF NOT EXISTS last_deep_dive_log TEXT;
