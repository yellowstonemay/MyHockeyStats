package com.myhockeystats.api;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/players")
public class PlayerProfileController {
    private final PlayerProfileService playerProfileService;
    private final JwtUtil jwtUtil;

    public PlayerProfileController(PlayerProfileService playerProfileService, JwtUtil jwtUtil) {
        this.playerProfileService = playerProfileService;
        this.jwtUtil = jwtUtil;
    }

    record CreateProfileRequest(String fullName, LocalDate birthdate, String location, String position) {}
    record UpdateProfileRequest(String fullName, String location, String position, String photoUrl) {}
    record ProfileResponse(Long id, String fullName, LocalDate birthdate, String location, String position, String photoUrl) {}

    @PostMapping
    public ResponseEntity<?> createProfile(@RequestBody CreateProfileRequest req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // Extract user from token
            String email = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.replace("Bearer ", "");
                email = jwtUtil.extractEmail(token);
            }
            if (email == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));

            // In a real app, fetch the User from UserService
            PlayerProfile profile = playerProfileService.createProfile(null, req.fullName(), req.birthdate(), req.location());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", profile.getId()));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{playerId}")
    public ResponseEntity<?> getProfile(@PathVariable Long playerId) {
        Optional<PlayerProfile> profile = playerProfileService.getProfileById(playerId);
        if (profile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PlayerProfile p = profile.get();
        return ResponseEntity.ok(new ProfileResponse(p.getId(), p.getFullName(), p.getBirthdate(), 
                                                      p.getLocation(), p.getPosition(), p.getPhotoUrl()));
    }

    @PutMapping("/{playerId}")
    public ResponseEntity<?> updateProfile(@PathVariable Long playerId, @RequestBody UpdateProfileRequest req) {
        Optional<PlayerProfile> profile = playerProfileService.getProfileById(playerId);
        if (profile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PlayerProfile updated = playerProfileService.updateProfile(profile.get(), req.fullName(), req.location(), req.position, req.photoUrl);
        return ResponseEntity.ok(Map.of("id", updated.getId()));
    }
}