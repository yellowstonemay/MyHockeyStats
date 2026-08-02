package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    record SignupRequest(String email, String password, String fullName) {}
    record LoginRequest(String email, String password) {}

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        try {
            User u = userService.register(req.email(), req.password(), req.fullName());
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
