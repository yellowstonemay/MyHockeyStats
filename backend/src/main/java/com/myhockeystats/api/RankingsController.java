package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.RankingLookupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Team / league rankings for the signed-in player, computed from the full
 * per-player season tables (AYHL career, THF/AHF rosters, NJ HS team stats).
 */
@RestController
@RequestMapping("/api/rankings")
public class RankingsController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RankingLookupService rankingLookupService;

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email);
    }

    /** GET /api/rankings — team + league rank per season for every identity link. */
    @GetMapping
    public ResponseEntity<?> rankings(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        return ResponseEntity.ok(Map.of("rankings", rankingLookupService.forUser(user.get().getId())));
    }

    /**
     * GET /api/rankings/team?source=..&season=..&teamId=..
     * Full team roster for a season, ordered by the ranking criteria
     * (points DESC, goals DESC, assists DESC) so the player can see why they
     * rank where they do. Authenticated only.
     */
    @GetMapping("/team")
    public ResponseEntity<?> teamRoster(
            @RequestParam String source,
            @RequestParam String season,
            @RequestParam String teamId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String sql = switch (source) {
            case "AYHL" -> """
                SELECT c.source_player_id AS player_id, min(c.player_name) AS name,
                       max(c.games_played) AS games, max(c.goals) AS goals,
                       max(c.assists) AS assists, max(c.points) AS points,
                       bool_or(r.position IN ('G', 'Goalie')) AS is_goalie
                FROM ayhl_player_career c
                JOIN ayhl_roster r ON r.player_id = c.source_player_id AND r.season_label = c.season_label
                WHERE r.team_id::text = ? AND c.season_label = ?
                GROUP BY c.source_player_id
                ORDER BY bool_or(r.position IN ('G', 'Goalie')) ASC,
                         max(c.points) DESC, max(c.goals) DESC, max(c.assists) DESC, c.source_player_id""";
            case "THF" -> """
                SELECT player_id, min(player_name) AS name, max(gp) AS games,
                       max(goals) AS goals, max(assists) AS assists, max(points) AS points,
                       bool_or(position = 'G') AS is_goalie
                FROM thf_rosters
                WHERE team_id::text = ? AND season_year::text = ?
                GROUP BY player_id
                ORDER BY bool_or(position = 'G') ASC,
                         max(points) DESC, max(goals) DESC, max(assists) DESC, player_id""";
            case "AHF" -> """
                SELECT player_id, min(player_name) AS name, max(gp) AS games,
                       max(goals) AS goals, max(assists) AS assists, max(points) AS points,
                       bool_or(position = 'G') AS is_goalie
                FROM ahf_rosters
                WHERE team_id::text = ? AND season_year::text = ?
                GROUP BY player_id
                ORDER BY bool_or(position = 'G') ASC,
                         max(points) DESC, max(goals) DESC, max(assists) DESC, player_id""";
            case "NJHS" -> """
                SELECT player_id, player_name AS name, NULL AS games, goals, assists, points,
                       (position = 'G') AS is_goalie
                FROM njhs_player_stats
                WHERE team_name = ? AND season_year::text = ?
                ORDER BY (position = 'G') ASC, points DESC, goals DESC, assists DESC, player_id""";
            default -> null;
        };
        if (sql == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid source"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, teamId, season);
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("rank", i + 1);
            // Goalies don't have skater stats — blank them so the UI doesn't show
            // misleading (and, for AYHL, corrupted) GP/G/A/PTS values.
            if (Boolean.TRUE.equals(rows.get(i).get("is_goalie"))) {
                rows.get(i).put("games", null);
                rows.get(i).put("goals", null);
                rows.get(i).put("assists", null);
                rows.get(i).put("points", null);
            }
        }
        return ResponseEntity.ok(Map.of("source", source, "season", season, "players", rows));
    }
}
