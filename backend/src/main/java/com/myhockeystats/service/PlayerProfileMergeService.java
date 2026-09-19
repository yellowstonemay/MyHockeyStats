package com.myhockeystats.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a duplicate player profile into the profile that should survive.
 *
 * <p>Auto name matching happily attaches the same source identity (for example
 * MYHockeyRankings team 1205) to every profile sharing a player's name, so the
 * same kid can end up with several profiles. A merge moves every child row of
 * the duplicate (seasons, games, manual stat overrides, source links, logins)
 * onto the target and then deletes the duplicate.
 *
 * <p>Rows that would collide with an existing row on the target are dropped
 * first, because the child tables carry unique keys on
 * {@code (player_id, source)} / {@code (player_id, season_year, game_id)}. The
 * only collision treated as an error is two different source ids for the same
 * source, which would silently rewrite history.
 */
@Service
public class PlayerProfileMergeService {

    private final JdbcTemplate jdbcTemplate;

    public PlayerProfileMergeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Thrown for merges the caller must fix first (bad ids, name mismatch). */
    public static class MergeRejectedException extends RuntimeException {
        public MergeRejectedException(String message) {
            super(message);
        }
    }

    @Transactional
    public Map<String, Object> merge(long sourceId, long targetId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (sourceId == targetId) {
            throw new MergeRejectedException("A profile cannot be merged into itself");
        }

        Map<String, Object> source = profile(sourceId);
        Map<String, Object> target = profile(targetId);
        if (source == null) {
            throw new MergeRejectedException("No player profile with id " + sourceId);
        }
        if (target == null) {
            throw new MergeRejectedException("No player profile with id " + targetId);
        }

        String sourceName = String.valueOf(source.get("full_name")).trim();
        String targetName = String.valueOf(target.get("full_name")).trim();
        if (!sourceName.equalsIgnoreCase(targetName)) {
            throw new MergeRejectedException(
                "Refusing to merge \"" + sourceName + "\" into \"" + targetName + "\": names differ");
        }

        assertNoConflictingLinks(sourceId, targetId, sourceName);

        summary.put("sourceProfileId", sourceId);
        summary.put("targetProfileId", targetId);
        summary.put("playerName", targetName);

        int performances = jdbcTemplate.update(
            "UPDATE game_performances SET player_profile_id = ? WHERE player_profile_id = ?",
            targetId, sourceId);
        int seasons = jdbcTemplate.update(
            "UPDATE seasons SET player_profile_id = ? WHERE player_profile_id = ?",
            targetId, sourceId);
        int insights = jdbcTemplate.update(
            "UPDATE ai_insights SET player_id = ? WHERE player_id = ?", targetId, sourceId);
        int deepDives = jdbcTemplate.update(
            "UPDATE deep_dive_requests SET player_id = ? WHERE player_id = ?", targetId, sourceId);

        jdbcTemplate.update("""
            DELETE FROM game_stat_overrides dup
            USING game_stat_overrides keep
            WHERE dup.player_profile_id = ? AND keep.player_profile_id = ?
              AND dup.source = keep.source AND dup.season_year = keep.season_year
              AND dup.game_id = keep.game_id
            """, sourceId, targetId);
        int overrides = jdbcTemplate.update(
            "UPDATE game_stat_overrides SET player_profile_id = ? WHERE player_profile_id = ?",
            targetId, sourceId);

        jdbcTemplate.update("""
            DELETE FROM player_source_links dup
            USING player_source_links keep
            WHERE dup.player_id = ? AND keep.player_id = ? AND dup.source = keep.source
            """, sourceId, targetId);
        int links = jdbcTemplate.update(
            "UPDATE player_source_links SET player_id = ? WHERE player_id = ?", targetId, sourceId);

        jdbcTemplate.update("""
            UPDATE user_players keep SET can_edit = TRUE
            FROM user_players dup
            WHERE dup.player_id = ? AND keep.player_id = ? AND keep.user_id = dup.user_id
              AND dup.can_edit AND NOT keep.can_edit
            """, sourceId, targetId);
        jdbcTemplate.update("""
            UPDATE user_players keep SET is_primary = TRUE
            FROM user_players dup
            WHERE dup.player_id = ? AND keep.player_id = ? AND keep.user_id = dup.user_id
              AND dup.is_primary AND NOT keep.is_primary
              AND NOT EXISTS (
                  SELECT 1 FROM user_players other
                  WHERE other.user_id = keep.user_id
                    AND other.player_id NOT IN (?, ?)
                    AND other.is_primary)
            """, sourceId, targetId, sourceId, targetId);
        jdbcTemplate.update("""
            DELETE FROM user_players dup
            USING user_players keep
            WHERE dup.player_id = ? AND keep.player_id = ? AND dup.user_id = keep.user_id
            """, sourceId, targetId);
        int logins = jdbcTemplate.update(
            "UPDATE user_players SET player_id = ? WHERE player_id = ?", targetId, sourceId);

        jdbcTemplate.update("""
            DELETE FROM mhr_team_links dup
            USING mhr_team_links keep
            WHERE dup.player_id = ? AND keep.player_id = ?
              AND dup.season_year = keep.season_year AND dup.team_id = keep.team_id
            """, sourceId, targetId);
        int teamLinks = jdbcTemplate.update(
            "UPDATE mhr_team_links SET player_id = ? WHERE player_id = ?", targetId, sourceId);

        // mhr_player_games.player_id is varchar and has no foreign key to player_profiles
        jdbcTemplate.update("""
            DELETE FROM mhr_player_games dup
            USING mhr_player_games keep
            WHERE dup.player_id = CAST(? AS text) AND keep.player_id = CAST(? AS text)
              AND dup.season_year = keep.season_year AND dup.game_id = keep.game_id
            """, sourceId, targetId);
        int mhrGames = jdbcTemplate.update(
            "UPDATE mhr_player_games SET player_id = CAST(? AS text) WHERE player_id = CAST(? AS text)",
            targetId, sourceId);

        // The surviving profile keeps its own identity; only blanks are filled in.
        jdbcTemplate.update("""
            UPDATE player_profiles SET
                birthdate = COALESCE(birthdate, CAST(? AS date)),
                position = COALESCE(position, NULLIF(CAST(? AS text), '')),
                location = COALESCE(location, NULLIF(CAST(? AS text), '')),
                photo_url = COALESCE(photo_url, CAST(? AS text)),
                owner_user_id = COALESCE(owner_user_id, CAST(? AS bigint)),
                updated_at = now()
            WHERE id = ?
            """, source.get("birthdate"), source.get("position"), source.get("location"),
            source.get("photo_url"), source.get("owner_user_id"), targetId);

        jdbcTemplate.update("DELETE FROM player_profiles WHERE id = ?", sourceId);

        summary.put("moved", Map.of(
            "seasons", seasons,
            "gamePerformances", performances,
            "gameStatOverrides", overrides,
            "sourceLinks", links,
            "logins", logins,
            "aiInsights", insights,
            "deepDiveRequests", deepDives,
            "mhrTeamLinks", teamLinks,
            "mhrGames", mhrGames));
        summary.put("message", "Merged " + sourceName + " #" + sourceId + " into #" + targetId + ".");
        return summary;
    }

    private Map<String, Object> profile(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, full_name, birthdate, position, location, photo_url, owner_user_id "
                + "FROM player_profiles WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Both profiles may already point at the same source identity (the usual
     * duplicate) — that row is dropped. Two different identities for one source
     * would have to be merged by hand, so the merge is refused.
     */
    private void assertNoConflictingLinks(long sourceId, long targetId, String playerName) {
        List<Map<String, Object>> conflicts = jdbcTemplate.queryForList("""
            SELECT s.source, s.source_player_id AS source_pid, t.source_player_id AS target_pid
            FROM player_source_links s
            JOIN player_source_links t ON t.source = s.source AND t.player_id = ?
            WHERE s.player_id = ? AND s.source_player_id IS DISTINCT FROM t.source_player_id
            """, targetId, sourceId);
        if (!conflicts.isEmpty()) {
            Map<String, Object> first = conflicts.get(0);
            throw new MergeRejectedException("Cannot merge " + playerName + ": profile " + sourceId
                + " is linked to " + first.get("source") + " id " + first.get("source_pid")
                + " while profile " + targetId + " is linked to id " + first.get("target_pid"));
        }
    }
}
