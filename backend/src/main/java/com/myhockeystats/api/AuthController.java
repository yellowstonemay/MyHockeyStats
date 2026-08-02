package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbcTemplate;

    public AuthController(UserService userService, JwtUtil jwtUtil, JdbcTemplate jdbcTemplate) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.jdbcTemplate = jdbcTemplate;
    }

    record SignupRequest(String email, String password, String fullName) {}
    record LoginRequest(String email, String password) {}

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        try {
            User u = userService.register(req.email(), req.password(), req.fullName());
            // Auto-queue an initial FULL (all-seasons) deep-dive for the new user.
            // The Mac mini poller picks it up, links the user, and populates stats.
            List<Integer> existing = jdbcTemplate.queryForList(
                "SELECT 1 FROM deep_dive_requests WHERE user_id = ? AND status IN ('PENDING','RUNNING') LIMIT 1",
                Integer.class, u.getId());
            if (existing.isEmpty()) {
                jdbcTemplate.update(
                    "INSERT INTO deep_dive_requests (id, user_id, scope, status, requested_at) " +
                    "VALUES (gen_random_uuid(), ?, 'ALL_SEASONS', 'PENDING', NOW())",
                    u.getId());
            }
            String token = jwtUtil.generateToken(u.getEmail());
            return ResponseEntity.ok(Map.of("token", token, "email", u.getEmail(), "isAdmin", u.isAdmin()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        return userService.findByEmail(req.email())
                .filter(u -> userService.verifyPassword(u, req.password()))
                .map(u -> {
                    userService.touchLastLogin(u);
                    return ResponseEntity.ok(Map.of(
                        "token", jwtUtil.generateToken(u.getEmail()),
                        "email", u.getEmail(),
                        "isAdmin", u.isAdmin()));
                })
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }
}
