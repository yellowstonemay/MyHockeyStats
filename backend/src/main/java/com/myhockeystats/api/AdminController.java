package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.DeepDiveService;
import com.myhockeystats.service.PlayerProfileMergeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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

    @Autowired
    private DeepDiveService deepDiveService;

    @Autowired
    private PlayerProfileMergeService mergeService;

    private Optional<User> resolveAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email).filter(User::isAdmin);
    }

    /**
     * GET /api/admin/players — all registered logins ordered by registration
     * time (newest first), with last login, the players attached to the login,
     * how many source identities those players are linked to, and the outcome of
     * the most recent deep-dive covering any of them.
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
                   STRING_AGG(DISTINCT pp.full_name, ', ') AS profile_name,
                   COUNT(DISTINCT up.player_id) AS player_count,
                   COUNT(DISTINCT psl.id) AS link_count,
                   STRING_AGG(DISTINCT psl.source, ',') AS sources,
                   last_dd.source                AS last_deep_dive_source,
                   last_dd.last_deep_dive_at     AS last_deep_dive_at,
                   last_dd.last_deep_dive_status AS last_deep_dive_status,
                   last_dd.last_deep_dive_error  AS last_deep_dive_error,
                   req.status                    AS deep_dive_request_status,
                   req.requested_at              AS deep_dive_requested_at,
                   req.started_at                AS deep_dive_request_started_at
            FROM users u
            LEFT JOIN user_players up ON up.user_id = u.id
            LEFT JOIN player_profiles pp ON pp.id = up.player_id
            LEFT JOIN player_source_links psl ON psl.player_id = up.player_id
            -- Last deep-dive RESULT for this login's players. last_verified_at
            -- cannot be used here: identity_link.py refreshes it on every run, so
            -- a source that fails every day still looks freshly verified.
            -- The outcome and the timestamp have to come from one row, so they
            -- are joined together instead of aggregated one by one.
            LEFT JOIN LATERAL (
                SELECT l.source, l.last_deep_dive_at,
                       l.last_deep_dive_status, l.last_deep_dive_error
                FROM player_source_links l
                JOIN user_players lup ON lup.player_id = l.player_id
                WHERE lup.user_id = u.id
                  AND l.last_deep_dive_at IS NOT NULL
                ORDER BY l.last_deep_dive_at DESC
                LIMIT 1
            ) last_dd ON TRUE
            -- Newest deep-dive request covering this login's players. An
            -- in-flight one wins over a finished one, so the row can tell the
            -- admin "a refresh is queued/running" and disable a second click.
            LEFT JOIN LATERAL (
                SELECT r.status, r.requested_at, r.started_at
                FROM deep_dive_requests r
                JOIN user_players rup ON rup.player_id = r.player_id
                WHERE rup.user_id = u.id
                ORDER BY (r.status IN ('PENDING', 'RUNNING')) DESC, r.requested_at DESC
                LIMIT 1
            ) req ON TRUE
            GROUP BY u.id, u.email, u.full_name, u.is_admin, u.created_at, u.last_login_at,
                     last_dd.source, last_dd.last_deep_dive_at,
                     last_dd.last_deep_dive_status, last_dd.last_deep_dive_error,
                     req.status, req.requested_at, req.started_at
            ORDER BY u.created_at DESC
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(Map.of("players", rows));
    }

    /**
     * GET /api/admin/profiles — every player profile with its owner and the
     * logins attached to it, so an admin can spot duplicates (two profiles for
     * one kid) and unowned/orphaned profiles. Newest first.
     */
    @GetMapping("/profiles")
    public ResponseEntity<?> listProfiles(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String sql = """
            SELECT pp.id, pp.full_name AS profile_name, pp.birthdate, pp.position, pp.location,
                   pp.owner_user_id, owner.email AS owner_email,
                   creator.email AS created_by_email,
                   pp.created_at,
                   STRING_AGG(DISTINCT u.email, ', ') AS attached_logins,
                   STRING_AGG(DISTINCT u.email, ', ')
                       FILTER (WHERE up.can_edit) AS editors
            FROM player_profiles pp
            LEFT JOIN users owner ON owner.id = pp.owner_user_id
            LEFT JOIN users creator ON creator.id = pp.created_by_user_id
            LEFT JOIN user_players up ON up.player_id = pp.id
            LEFT JOIN users u ON u.id = up.user_id
            GROUP BY pp.id, pp.full_name, pp.birthdate, pp.position, pp.location,
                     pp.owner_user_id, owner.email, creator.email, pp.created_at
            ORDER BY pp.created_at DESC, pp.id DESC
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(Map.of("profiles", rows));
    }

    /**
     * POST /api/admin/profiles/{sourceId}/merge — fold a duplicate profile into
     * the profile that should survive, then delete the duplicate. The two
     * profiles must carry the same player name; everything attached to the
     * duplicate (seasons, games, manual G/A, source links, logins) is moved.
     */
    @PostMapping("/profiles/{sourceId}/merge")
    public ResponseEntity<?> mergeProfiles(
            @PathVariable Long sourceId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        Object rawTarget = body == null ? null : body.get("targetProfileId");
        if (rawTarget == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetProfileId is required"));
        }
        long targetId;
        try {
            targetId = Long.parseLong(String.valueOf(rawTarget).trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetProfileId must be a number"));
        }
        try {
            return ResponseEntity.ok(mergeService.merge(sourceId, targetId));
        } catch (PlayerProfileMergeService.MergeRejectedException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/admin/deep-dive/{userId} — enqueue a FULL (all-seasons) deep-dive
     * for every player attached to a login. Requests are deduped per player, so
     * logins sharing a player never trigger duplicate scrapes.
     */
    @PostMapping("/deep-dive/{userId}")
    public ResponseEntity<?> triggerDeepDive(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        DeepDiveService.QueueResult result = deepDiveService.enqueueForUser(userId);
        return ResponseEntity.ok(Map.of(
            "message", result.message(),
            "queued", result.queued()));
    }

    /**
     * GET /api/admin/deep-dive/requests/{userId} — the deep-dive queue for every
     * player attached to a login, newest first.
     *
     * A click on the admin page enqueues one request per player of that login
     * and then waits for the Mac mini poller, which can be minutes away, so the
     * page polls this to show what happened to the click.
     */
    @GetMapping("/deep-dive/requests/{userId}")
    public ResponseEntity<?> listDeepDiveRequests(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String sql = """
            SELECT r.id::text AS id, r.player_id,
                   pp.full_name AS player_name,
                   r.status, r.requested_at, r.started_at, r.completed_at, r.error,
                   links.sources AS sources
            FROM deep_dive_requests r
            JOIN player_profiles pp ON pp.id = r.player_id
            JOIN user_players up ON up.player_id = r.player_id AND up.user_id = ?
            LEFT JOIN LATERAL (
                SELECT STRING_AGG(DISTINCT l.source, ',') AS sources
                FROM player_source_links l
                WHERE l.player_id = r.player_id AND l.link_state = 'CONFIRMED'
            ) links ON TRUE
            WHERE r.status IN ('PENDING', 'RUNNING')
               OR r.requested_at > NOW() - INTERVAL '7 days'
            ORDER BY r.requested_at DESC
            LIMIT 25
            """;
        List<Map<String, Object>> requests = jdbcTemplate.queryForList(sql, userId);
        long active = requests.stream()
            .filter(r -> "PENDING".equals(r.get("status")) || "RUNNING".equals(r.get("status")))
            .count();
        Map<String, Object> body = new HashMap<>();
        body.put("requests", requests);
        body.put("active", active);
        return ResponseEntity.ok(body);
    }

    /** GET /api/admin/messages — all support/report messages, NEW first. */
    @GetMapping("/messages")
    public ResponseEntity<?> listSupportMessages(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        List<Map<String, Object>> messages = jdbcTemplate.query(
            "SELECT m.id::text, m.user_id, u.email, u.full_name AS user_name, " +
            "       m.category, m.message, m.status, m.created_at " +
            "FROM support_messages m JOIN users u ON u.id = m.user_id " +
            "ORDER BY (m.status = 'NEW') DESC, m.created_at DESC",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getString("id"));
                m.put("email", rs.getString("email"));
                m.put("userName", rs.getString("user_name"));
                m.put("category", rs.getString("category"));
                m.put("message", rs.getString("message"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
                return m;
            });
        return ResponseEntity.ok(Map.of("messages", messages));
    }

    /** POST /api/admin/messages/{id}/resolve — mark a report resolved. */
    @PostMapping("/messages/{id}/resolve")
    public ResponseEntity<?> resolveSupportMessage(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        int updated = jdbcTemplate.update(
            "UPDATE support_messages SET status = 'RESOLVED' WHERE id = ?::uuid", id);
        if (updated == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "Message not found"));
        }
        return ResponseEntity.ok(Map.of("message", "Marked resolved"));
    }
}
