package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin endpoints. Access is gated by the is_admin flag on the authenticated user.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Optional<User> resolveAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email).filter(User::isAdmin);
    }

    /**
     * GET /api/admin/players — all registered users ordered by registration
     * time (newest first), with last login, identity-link count/sources, and
     * the last time the deep-dive refreshed that user.
     */
    @GetMapping("/players")
    public ResponseEntity<?> listPlayers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String sql = """
            SELECT u.id, u.email, u.full_name AS user_name, u.is_admin,
                   u.created_at, u.last_login_at,
                   pp.full_name AS profile_name,
                   COUNT(pim.id) AS link_count,
                   MAX(pim.last_verified_at) AS last_deep_dive_at,
                   STRING_AGG(DISTINCT pim.source, ',') AS sources
            FROM users u
            LEFT JOIN player_profiles pp ON pp.user_id = u.id
            LEFT JOIN player_identity_map pim ON pim.user_id = u.id
            GROUP BY u.id, u.email, u.full_name, u.is_admin, u.created_at,
                     u.last_login_at, pp.full_name
            ORDER BY u.created_at DESC
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(Map.of("players", rows));
    }

    /**
     * POST /api/admin/deep-dive/{userId} — enqueue a FULL (all-seasons) deep-dive
     * for a user. The Mac mini poller picks it up and runs the per-user
     * full-career scrape. Dedupes against pending/running requests.
     */
    @PostMapping("/deep-dive/{userId}")
    public ResponseEntity<?> triggerDeepDive(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        List<Integer> pending = jdbcTemplate.queryForList(
            "SELECT 1 FROM deep_dive_requests WHERE user_id = ? AND status IN ('PENDING','RUNNING') LIMIT 1",
            Integer.class, userId);
        if (!pending.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "message", "A deep-dive is already queued or running for this user.",
                "queued", false));
        }
        jdbcTemplate.update(
            "INSERT INTO deep_dive_requests (id, user_id, scope, status, requested_at) " +
            "VALUES (gen_random_uuid(), ?, 'ALL_SEASONS', 'PENDING', NOW())",
            userId);
        return ResponseEntity.ok(Map.of(
            "message", "Full (all-seasons) deep-dive queued for user " + userId + ".",
            "queued", true));
    }
}
