package com.myhockeystats.service.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the MYHockeyRankings positions of the team(s) a player is linked to.
 *
 * MHR ranks teams, not players: its team page carries a "Rankings" card listing
 * the team's position in each category it is ranked in (state, national, tier),
 * with a week-over-week arrow. The scrape stores one row per team/season/category
 * in {@code mhr_team_rankings} (see {@code scripts/mhr/mhr_ingest.py}), and this
 * service joins it back through {@code mhr_team_links} for the player.
 */
@Service
public class MhrRankingLookupService {

    private final JdbcTemplate jdbcTemplate;

    /** Cached "does the table exist" answer (null = not checked yet). */
    private Boolean tableAvailable;

    public MhrRankingLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * One block per (season, team) with the ranks MHR published for it, newest
     * season first. An empty list means no ranking has been scraped yet.
     */
    public List<Map<String, Object>> rankingsForPlayer(Long profileId) {
        if (profileId == null || !tableAvailable()) {
            return List.of();
        }
        String sql = "SELECT l.season_year, l.team_id, l.team_name, l.division,"
            + " r.category, r.rank_position, r.trend, r.rank_url, r.scraped_at"
            + " FROM mhr_team_links l"
            + " JOIN mhr_team_rankings r ON r.team_id = l.team_id AND r.season_year = l.season_year"
            + " WHERE l.player_id = ? AND l.link_state = 'confirmed'"
            + " ORDER BY l.season_year DESC, l.team_id, r.rank_position NULLS LAST";

        Map<String, Map<String, Object>> seasons = new LinkedHashMap<>();
        // mhr_team_links.player_id is BIGINT (the profile id), unlike
        // mhr_player_games.player_id which stores it as text.
        jdbcTemplate.query(sql, ps -> ps.setLong(1, profileId), (rs) -> {
            int seasonYear = rs.getInt("season_year");
            String teamId = rs.getString("team_id");
            String key = seasonYear + "|" + teamId;
            Map<String, Object> season = seasons.get(key);
            if (season == null) {
                season = new LinkedHashMap<>();
                season.put("seasonYear", seasonYear);
                season.put("seasonLabel", seasonYear + "-" + (seasonYear + 1) + " Season");
                season.put("teamId", teamId);
                season.put("teamName", rs.getString("team_name"));
                season.put("division", rs.getString("division"));
                season.put("entries", new ArrayList<Map<String, Object>>());
                seasons.put(key, season);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", rs.getString("category"));
            entry.put("rank", rs.getObject("rank_position"));
            entry.put("trend", rs.getString("trend"));
            entry.put("url", rs.getString("rank_url"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) season.get("entries");
            entries.add(entry);

            Timestamp scraped = rs.getTimestamp("scraped_at");
            Instant asOf = scraped == null ? null : scraped.toInstant();
            Object current = season.get("asOf");
            if (asOf != null && (current == null || asOf.isAfter((Instant) current))) {
                season.put("asOf", asOf);
            }
        });

        List<Map<String, Object>> out = new ArrayList<>(seasons.values());
        for (Map<String, Object> season : out) {
            Instant asOf = (Instant) season.get("asOf");
            season.put("asOf", asOf == null ? null : asOf.toString());
        }
        return out;
    }

    private boolean tableAvailable() {
        if (tableAvailable == null) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = current_schema() AND table_name = 'mhr_team_rankings'",
                Integer.class);
            tableAvailable = count != null && count > 0;
        }
        return tableAvailable;
    }
}
