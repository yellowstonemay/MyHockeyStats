package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Notifications for the authenticated user. A "DEEP_DIVE" notification is
 * created when a deep-dive is queued (signup or admin trigger) and resolved by
 * the Mac mini poller once the deep-dive finishes.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email);
    }

    /** GET /api/notifications — the current user's active notifications. */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id::text, type, title, message, status, created_at " +
            "FROM notifications WHERE user_id = ? AND status = 'ACTIVE' " +
            "ORDER BY created_at DESC",
            user.get().getId());
        return ResponseEntity.ok(Map.of("notifications", rows));
    }

    /** POST /api/notifications/{id}/dismiss — mark a notification dismissed. */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<?> dismiss(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        int updated = jdbcTemplate.update(
            "UPDATE notifications SET status = 'DISMISSED', resolved_at = NOW() " +
            "WHERE id = ?::uuid AND user_id = ?",
            id, user.get().getId());
        if (updated == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "Notification not found"));
        }
        return ResponseEntity.ok(Map.of("dismissed", true));
    }
}
