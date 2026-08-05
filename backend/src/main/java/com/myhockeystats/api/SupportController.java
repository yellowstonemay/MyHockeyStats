package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * "Report a problem" — lets a signed-in user send a message to the admin
 * (incorrect data, missing season, wrong player link, etc.). Stored in the
 * support_messages table; the admin reviews them on the admin page.
 */
@RestController
@RequestMapping("/api/support")
public class SupportController {

    static final List<String> CATEGORIES = List.of(
        "Incorrect data", "Missing season", "Wrong player link", "Other");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public SupportController(JwtUtil jwtUtil, UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
    }

    /** POST /api/support/messages — submit a report to the admin. */
    @PostMapping("/messages")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String category = body.get("category");
        String message = body.get("message");
        if (category == null || category.isBlank() || !CATEGORIES.contains(category)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please choose a category"));
        }
        if (message == null || message.isBlank() || message.trim().length() < 3) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please enter a short description"));
        }
        if (message.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is too long (max 2000 characters)"));
        }
        jdbcTemplate.update(
            "INSERT INTO support_messages (id, user_id, category, message, status, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'NEW', NOW())",
            user.get().getId(), category, message.trim());
        return ResponseEntity.ok(Map.of(
            "message", "Report submitted. The admin will take a look — thanks!",
            "created", true));
    }

    /** GET /api/support/messages — the current user's own reports. */
    @GetMapping("/messages")
    public ResponseEntity<?> listMine(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<Map<String, Object>> messages = jdbcTemplate.query(
            "SELECT id::text, category, message, status, created_at " +
            "FROM support_messages WHERE user_id = ? ORDER BY created_at DESC LIMIT 20",
            (rs, rn) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getString("id"));
                m.put("category", rs.getString("category"));
                m.put("message", rs.getString("message"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
                return m;
            }, user.get().getId());
        return ResponseEntity.ok(Map.of("messages", messages));
    }
}
