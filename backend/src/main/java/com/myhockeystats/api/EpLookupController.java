package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Elite Prospects (EP) on-tap lookup — a fallback for the "Following" feature.
 *
 * When a player (e.g. Mason Sweeney) has history on Elite Prospects but isn't
 * in the locally scraped AYHL/THF/AHF/NJHS data, the UI can:
 *   1. POST /api/ep/lookup {name}   -> creates a PENDING SEARCH request
 *   2. GET  /api/ep/lookup/{id}     -> poll: status + candidates when done
 *   3. POST /api/follows {source:"EP", ...} -> follows the player AND enqueues
 *      a CAREER request to fetch their full EP career history.
 *
 * A Python poller (scripts/deep_dive/ep_lookup.py) drains the request table.
 */
@RestController
@RequestMapping("/api/ep")
public class EpLookupController {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public EpLookupController(JwtUtil jwtUtil, UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.replace("Bearer ", "").trim();
        String email = jwtUtil.extractEmail(token);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
    }

    /** POST /api/ep/lookup body {name} — create a SEARCH request (dedupes active ones). */
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody Map<String, String> body,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String name = body.get("name");
        if (name == null || name.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Type at least 2 characters to search"));
        }
        name = name.trim();
        long uid = user.get().getId();

        // Reuse an in-flight search for the same user+name.
        List<String> existing = jdbcTemplate.query(
            "SELECT id::text FROM ep_lookup_requests " +
            "WHERE user_id = ? AND type = 'SEARCH' AND lower(name) = lower(?) AND status IN ('PENDING','RUNNING') " +
            "ORDER BY created_at DESC LIMIT 1",
            (rs, rn) -> rs.getString(1), uid, name);
        if (!existing.isEmpty()) {
            return ResponseEntity.ok(Map.of("requestId", existing.get(0), "status", "PENDING"));
        }

        jdbcTemplate.update(
            "INSERT INTO ep_lookup_requests (id, user_id, type, name, status, created_at) " +
            "VALUES (gen_random_uuid(), ?, 'SEARCH', ?, 'PENDING', NOW())",
            uid, name);
        String requestId = jdbcTemplate.queryForObject(
            "SELECT id::text FROM ep_lookup_requests WHERE user_id = ? AND type='SEARCH' AND name = ? " +
            "ORDER BY created_at DESC LIMIT 1", String.class, uid, name);
        return ResponseEntity.ok(Map.of("requestId", requestId, "status", "PENDING"));
    }

    /** GET /api/ep/lookup/{requestId} — poll for status + candidates. */
    @GetMapping("/lookup/{requestId}")
    public ResponseEntity<?> lookupStatus(@PathVariable("requestId") String requestId,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();

        List<Map<String, Object>> req = jdbcTemplate.query(
            "SELECT status, error FROM ep_lookup_requests WHERE id = ?::uuid AND user_id = ?",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("status", rs.getString("status"));
                m.put("error", rs.getString("error"));
                return m;
            }, requestId, uid);
        if (req.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Lookup not found"));
        }
        Map<String, Object> out = new HashMap<>(req.get(0));

        if ("COMPLETED".equals(out.get("status"))) {
            List<Map<String, Object>> candidates = jdbcTemplate.query(
                "SELECT ep_player_id, player_name, position, year_of_birth, latest_team, latest_league, league_experience " +
                "FROM ep_lookup_candidates WHERE request_id = ?::uuid ORDER BY player_name",
                (rs, rn) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("epPlayerId", rs.getString("ep_player_id"));
                    m.put("playerName", rs.getString("player_name"));
                    m.put("position", rs.getString("position"));
                    Object yob = rs.getObject("year_of_birth");
                    m.put("yearOfBirth", yob == null ? null : ((Number) yob).intValue());
                    m.put("latestTeam", rs.getString("latest_team"));
                    m.put("latestLeague", rs.getString("latest_league"));
                    m.put("leagueExperience", rs.getString("league_experience"));
                    return m;
                }, requestId);
            out.put("candidates", candidates);
        } else {
            out.put("candidates", List.of());
        }
        return ResponseEntity.ok(out);
    }

    /** POST /api/ep/career body {epPlayerId, playerName} — enqueue a CAREER request (dedupes active ones). */
    @PostMapping("/career")
    public ResponseEntity<?> enqueueCareer(@RequestBody Map<String, String> body,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String epId = body.get("epPlayerId");
        if (epId == null || epId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "epPlayerId is required"));
        }
        long uid = user.get().getId();

        // If we already have career data, nothing to do.
        List<Integer> have = jdbcTemplate.query(
            "SELECT 1 FROM ep_player_career WHERE ep_player_id = ? LIMIT 1",
            (rs, rn) -> rs.getInt(1), epId);
        if (!have.isEmpty()) {
            return ResponseEntity.ok(Map.of("requestId", (String) null, "status", "COMPLETED"));
        }

        // Reuse an in-flight career request for this player.
        List<String> existing = jdbcTemplate.query(
            "SELECT id::text FROM ep_lookup_requests " +
            "WHERE type = 'CAREER' AND ep_player_id = ? AND status IN ('PENDING','RUNNING') " +
            "ORDER BY created_at DESC LIMIT 1",
            (rs, rn) -> rs.getString(1), epId);
        if (!existing.isEmpty()) {
            return ResponseEntity.ok(Map.of("requestId", existing.get(0), "status", "PENDING"));
        }

        String name = body.get("playerName");
        jdbcTemplate.update(
            "INSERT INTO ep_lookup_requests (id, user_id, type, name, ep_player_id, status, created_at) " +
            "VALUES (gen_random_uuid(), ?, 'CAREER', ?, ?, 'PENDING', NOW())",
            uid, name, epId);
        String requestId = jdbcTemplate.queryForObject(
            "SELECT id::text FROM ep_lookup_requests WHERE type='CAREER' AND ep_player_id = ? " +
            "ORDER BY created_at DESC LIMIT 1", String.class, epId);
        return ResponseEntity.ok(Map.of("requestId", requestId, "status", "PENDING"));
    }

    /** GET /api/ep/career/{epPlayerId} — career history + lookup status. */
    @GetMapping("/career/{epPlayerId}")
    public ResponseEntity<?> careerStatus(@PathVariable("epPlayerId") String epPlayerId,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();

        // Pending/in-flight request for this player?
        List<String> status = jdbcTemplate.query(
            "SELECT status FROM ep_lookup_requests WHERE type = 'CAREER' AND ep_player_id = ? AND user_id = ? " +
            "ORDER BY created_at DESC LIMIT 1",
            (rs, rn) -> rs.getString(1), epPlayerId, uid);

        List<Map<String, Object>> career = jdbcTemplate.query(
            "SELECT season_label, team_name, league, games_played, goals, assists, points, pim " +
            "FROM ep_player_career WHERE ep_player_id = ? " +
            "ORDER BY season_label, team_name",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("season", rs.getString("season_label"));
                m.put("team", rs.getString("team_name"));
                m.put("league", rs.getString("league"));
                Object gp = rs.getObject("games_played");
                m.put("games", gp == null ? null : ((Number) gp).intValue());
                Object g = rs.getObject("goals");
                m.put("goals", g == null ? null : ((Number) g).intValue());
                Object a = rs.getObject("assists");
                m.put("assists", a == null ? null : ((Number) a).intValue());
                Object p = rs.getObject("points");
                m.put("points", p == null ? null : ((Number) p).intValue());
                Object pi = rs.getObject("pim");
                m.put("pim", pi == null ? null : ((Number) pi).intValue());
                return m;
            }, epPlayerId);

        String st = (!status.isEmpty() && career.isEmpty()) ? status.get(0) : "COMPLETED";
        return ResponseEntity.ok(Map.of(
            "status", st,
            "career", career,
            "profileUrl", "https://www.eliteprospects.com/player/" + epPlayerId
        ));
    }
}
