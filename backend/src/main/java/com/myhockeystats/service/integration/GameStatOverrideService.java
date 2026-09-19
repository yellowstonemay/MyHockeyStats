package com.myhockeystats.service.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Manual G/A/PIM entered by a human for a game that also comes from a scraper.
 * The values live in their own table, so re-importing a source never overwrites
 * them; the game history lookup merges them back in and flags the row as edited.
 */
@Service
public class GameStatOverrideService {

    private final JdbcTemplate jdbcTemplate;

    public GameStatOverrideService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Insert the manual entry, or replace the previous one for the same game. */
    public void save(Long playerId, String source, Integer seasonYear, String gameId,
                     Integer goals, Integer assists, Integer pim, Long enteredByUserId) {
        jdbcTemplate.update(
            "INSERT INTO game_stat_overrides"
                + " (player_profile_id, source, season_year, game_id, goals, assists, pim, entered_by_user_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (player_profile_id, source, season_year, game_id) DO UPDATE SET"
                + " goals = EXCLUDED.goals, assists = EXCLUDED.assists, pim = EXCLUDED.pim,"
                + " entered_by_user_id = EXCLUDED.entered_by_user_id, updated_at = now()",
            playerId, source, seasonYear, gameId, goals, assists, pim, enteredByUserId);
    }

    /** Drop the manual entry so the scraped value shows again. */
    public boolean delete(Long playerId, String source, Integer seasonYear, String gameId) {
        return jdbcTemplate.update(
            "DELETE FROM game_stat_overrides"
                + " WHERE player_profile_id = ? AND source = ? AND season_year = ? AND game_id = ?",
            playerId, source, seasonYear, gameId) > 0;
    }
}
