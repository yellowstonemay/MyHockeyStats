package com.myhockeystats.api;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/players")
public class PlayerProfileController {
    private static final Pattern BIRTH_MONTH_YEAR_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])/\\d{4}$");
    private static final DateTimeFormatter BIRTH_MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MM/yyyy");

    private final PlayerProfileService playerProfileService;
    private final JwtUtil jwtUtil;

    public PlayerProfileController(PlayerProfileService playerProfileService, JwtUtil jwtUtil) {
        this.playerProfileService = playerProfileService;
        this.jwtUtil = jwtUtil;
    }

    record CreateProfileRequest(String fullName, String birthMonthYear, String location, String position) {}
    record UpdateProfileRequest(String fullName, String birthMonthYear, String location, String position, String photoUrl) {}
    record ProfileResponse(Long id, String fullName, String birthMonthYear, String location, String position, String photoUrl) {}

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
            LocalDate birthdate = parseBirthMonthYear(req.birthMonthYear());
            PlayerProfile profile = playerProfileService.createProfile(null, req.fullName(), birthdate, req.location());
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
        return ResponseEntity.ok(new ProfileResponse(p.getId(), p.getFullName(), formatBirthMonthYear(p.getBirthdate()), 
                                                      p.getLocation(), p.getPosition(), p.getPhotoUrl()));
    }

    @PutMapping("/{playerId}")
    public ResponseEntity<?> updateProfile(@PathVariable Long playerId, @RequestBody UpdateProfileRequest req) {
        Optional<PlayerProfile> profile = playerProfileService.getProfileById(playerId);
        if (profile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LocalDate birthdate = null;
        if (req.birthMonthYear() != null) {
            birthdate = parseBirthMonthYear(req.birthMonthYear());
        }
        PlayerProfile updated = playerProfileService.updateProfile(
                profile.get(),
                req.fullName(),
                birthdate,
                req.location(),
                req.position,
                req.photoUrl);
        return ResponseEntity.ok(Map.of("id", updated.getId()));
    }

    private LocalDate parseBirthMonthYear(String birthMonthYear) {
        if (birthMonthYear == null || birthMonthYear.isBlank()) {
            throw new IllegalArgumentException("birthMonthYear is required in MM/YYYY format");
        }
        if (!BIRTH_MONTH_YEAR_PATTERN.matcher(birthMonthYear).matches()) {
            throw new IllegalArgumentException("birthMonthYear must be in MM/YYYY format");
        }

        try {
            YearMonth yearMonth = YearMonth.parse(birthMonthYear, BIRTH_MONTH_YEAR_FORMATTER);
            return yearMonth.atDay(1);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("birthMonthYear must be in MM/YYYY format");
        }
    }

    private String formatBirthMonthYear(LocalDate birthdate) {
        if (birthdate == null) {
            return null;
        }
        return birthdate.format(BIRTH_MONTH_YEAR_FORMATTER);
    }
}