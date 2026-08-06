package com.myhockeystats.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team / league rankings per season for a player, computed from the full
 * per-player season tables (AYHL career, THF/AHF rosters, NJ HS team stats).
 *
 * Shared by {@code RankingsController} (GUI tab) and {@code PlayerReportService}
 * (unified report + AI insights).
 */
@Service
public class RankingLookupService {

    private final JdbcTemplate jdbcTemplate;

    public RankingLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** All rankings for every confirmed identity link of a user. */
    public List<Map<String, Object>> forUser(long userId) {
        List<Map<String, Object>> links = jdbcTemplate.queryForList(
            "SELECT source, source_player_id FROM player_identity_map " +
            "WHERE user_id = ? AND link_state = 'CONFIRMED'", userId);
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> link : links) {
            String source = (String) link.get("source");
            String playerId = (String) link.get("source_player_id");
            if (source == null || playerId == null) continue;
            all.addAll(computeRankings(source, playerId));
        }
        return all;
    }

    public List<Map<String, Object>> computeRankings(String source, String playerId) {
        String sql = switch (source) {
            case "AYHL" -> """
                WITH goalies AS (
                  SELECT DISTINCT player_id, season_label FROM ayhl_roster
                  WHERE position IN ('G', 'Goalie')
                ),
                roster AS (
                  SELECT DISTINCT player_id, season_label, team_id, team_name FROM ayhl_roster
                ),
                team AS (
                  SELECT c.source_player_id AS player_id, c.season_label AS season,
                         COALESCE(r.team_id::text, c.team_name) AS team_key,
                         COALESCE(r.team_name, c.team_name) AS team,
                         c.games_played AS games, c.goals, c.assists, c.points,
                         RANK() OVER (PARTITION BY COALESCE(r.team_id::text, c.team_name), c.season_label ORDER BY c.points DESC, c.goals DESC, c.assists DESC) AS team_rank,
                         COUNT(*) OVER (PARTITION BY COALESCE(r.team_id::text, c.team_name), c.season_label) AS team_size
                  FROM ayhl_player_career c
                  LEFT JOIN roster r ON r.player_id = c.source_player_id AND r.season_label = c.season_label
                  LEFT JOIN goalies g ON g.player_id = c.source_player_id AND g.season_label = c.season_label
                  WHERE g.player_id IS NULL
                ),
                league AS (
                  SELECT c.source_player_id AS player_id, c.season_label AS season,
                         RANK() OVER (PARTITION BY c.season_label ORDER BY c.points DESC, c.goals DESC, c.assists DESC) AS league_rank,
                         COUNT(*) OVER (PARTITION BY c.season_label) AS league_size
                  FROM ayhl_player_career c
                  LEFT JOIN goalies g ON g.player_id = c.source_player_id AND g.season_label = c.season_label
                  WHERE g.player_id IS NULL
                )
                SELECT t.player_id, t.season, t.team_key, t.team, t.games, t.goals, t.assists, t.points,
                       t.team_rank, t.team_size, l.league_rank, l.league_size
                FROM team t
                JOIN league l ON l.player_id = t.player_id AND l.season = t.season
                WHERE t.player_id = ? ORDER BY t.season DESC""";
            case "THF" -> """
                WITH r AS (
                  SELECT player_id, season_year::text AS season, team_id::text AS team_key,
                         team_name AS team, gp AS games, goals, assists, points,
                         RANK() OVER (PARTITION BY team_id, season_year ORDER BY points DESC, goals DESC, assists DESC) AS team_rank,
                         COUNT(*) OVER (PARTITION BY team_id, season_year) AS team_size,
                         RANK() OVER (PARTITION BY season_year ORDER BY points DESC, goals DESC, assists DESC) AS league_rank,
                         COUNT(*) OVER (PARTITION BY season_year) AS league_size
                  FROM thf_rosters
                  WHERE position IS NULL OR position <> 'G'
                )
                SELECT * FROM r WHERE player_id = ? ORDER BY season DESC""";
            case "AHF" -> """
                WITH r AS (
                  SELECT player_id, season_year::text AS season, team_id::text AS team_key,
                         team_name AS team, gp AS games, goals, assists, points,
                         RANK() OVER (PARTITION BY team_id, season_year ORDER BY points DESC, goals DESC, assists DESC) AS team_rank,
                         COUNT(*) OVER (PARTITION BY team_id, season_year) AS team_size,
                         RANK() OVER (PARTITION BY season_year ORDER BY points DESC, goals DESC, assists DESC) AS league_rank,
                         COUNT(*) OVER (PARTITION BY season_year) AS league_size
                  FROM ahf_rosters
                  WHERE position IS NULL OR position <> 'G'
                )
                SELECT * FROM r WHERE player_id = ? ORDER BY season DESC""";
            case "NJHS" -> """
                WITH r AS (
                  SELECT s.player_id, s.season_year::text AS season, s.team_name AS team_key,
                         s.team_name AS team,
                         c.games_played AS games, s.goals, s.assists, s.points,
                         RANK() OVER (PARTITION BY s.team_name, s.season_year ORDER BY s.points DESC, s.goals DESC, s.assists DESC) AS team_rank,
                         COUNT(*) OVER (PARTITION BY s.team_name, s.season_year) AS team_size,
                         RANK() OVER (PARTITION BY s.season_year ORDER BY s.points DESC, s.goals DESC, s.assists DESC) AS league_rank,
                         COUNT(*) OVER (PARTITION BY s.season_year) AS league_size
                  FROM njhs_player_stats s
                  LEFT JOIN njhs_player_career c ON c.source_player_id = s.player_id AND c.season_year = s.season_year
                  WHERE s.position IS NULL OR s.position <> 'G'
                )
                SELECT * FROM r WHERE player_id = ? ORDER BY season DESC""";
            default -> null;
        };
        if (sql == null) {
            return List.of();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, playerId);
        // Dedupe per (season) keeping the best (max points) row.
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String season = String.valueOf(row.get("season"));
            Map<String, Object> existing = best.get(season);
            if (existing == null || num(row.get("points")) > num(existing.get("points"))) {
                best.put(season, row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : best.values()) {
            int leagueRank = row.get("league_rank") == null ? 0 : ((Number) row.get("league_rank")).intValue();
            int leagueSize = row.get("league_size") == null ? 0 : ((Number) row.get("league_size")).intValue();
            int teamRank = row.get("team_rank") == null ? 0 : ((Number) row.get("team_rank")).intValue();
            int teamSize = row.get("team_size") == null ? 0 : ((Number) row.get("team_size")).intValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", source);
            item.put("season", row.get("season"));
            item.put("team", row.get("team"));
            item.put("teamKey", row.get("team_key"));
            item.put("playerId", row.get("player_id"));
            item.put("games", row.get("games"));
            item.put("goals", row.get("goals"));
            item.put("assists", row.get("assists"));
            item.put("points", row.get("points"));
            item.put("teamRank", teamRank);
            item.put("teamSize", teamSize);
            item.put("teamPercentile", pct(teamRank, teamSize));
            item.put("leagueRank", leagueRank);
            item.put("leagueSize", leagueSize);
            item.put("leaguePercentile", pct(leagueRank, leagueSize));
            out.add(item);
        }
        return out;
    }

    private static int num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static double pct(int rank, int size) {
        if (size <= 0) return 0;
        return Math.round((1.0 - (double) rank / size) * 1000.0) / 10.0;
    }
}
