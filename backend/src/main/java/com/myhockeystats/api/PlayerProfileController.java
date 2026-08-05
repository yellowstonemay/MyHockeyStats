package com.myhockeystats.api;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
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
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public PlayerProfileController(PlayerProfileService playerProfileService, JwtUtil jwtUtil,
                                   UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.playerProfileService = playerProfileService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    record CreateProfileRequest(String fullName, String birthMonthYear, String location, String position) {}
    record UpdateProfileRequest(String fullName, String birthMonthYear, String location, String position, String photoUrl) {}
    record ProfileResponse(Long id, String fullName, String birthMonthYear, String location, String position, String photoUrl) {}

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email);
    }

    /** GET /api/players/me — the signed-in user's own profile. */
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        Optional<PlayerProfile> profile = playerProfileService.getProfileByUserId(user.get().getId());
        if (profile.isEmpty()) {
            return ResponseEntity.ok(Map.of("profile", (Object) null));
        }
        PlayerProfile p = profile.get();
        return ResponseEntity.ok(Map.of("profile", new ProfileResponse(
            p.getId(), p.getFullName(), formatBirthMonthYear(p.getBirthdate()),
            p.getLocation(), p.getPosition(), p.getPhotoUrl())));
    }

    /** PUT /api/players/me — create-or-update the signed-in user's profile. */
    @PutMapping("/me")
    public ResponseEntity<?> updateMyProfile(
            @RequestBody UpdateProfileRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        try {
            LocalDate birthdate = null;
            if (req.birthMonthYear() != null && !req.birthMonthYear().isBlank()) {
                birthdate = parseBirthMonthYear(req.birthMonthYear());
            }
            final LocalDate bd = birthdate;
            PlayerProfile profile = playerProfileService.getProfileByUserId(user.get().getId())
                .orElseGet(() -> playerProfileService.createProfile(
                    user.get(), req.fullName(), bd, req.location()));
            PlayerProfile updated = playerProfileService.updateProfile(
                profile, req.fullName(), birthdate, req.location(), req.position(), req.photoUrl());
            return ResponseEntity.ok(Map.of("profile", new ProfileResponse(
                updated.getId(), updated.getFullName(), formatBirthMonthYear(updated.getBirthdate()),
                updated.getLocation(), updated.getPosition(), updated.getPhotoUrl())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/players/me/deep-dive — enqueue a full deep-dive for the signed-in user. */
    @PostMapping("/me/deep-dive")
    public ResponseEntity<?> requestDeepDive(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<Integer> pending = jdbcTemplate.queryForList(
            "SELECT 1 FROM deep_dive_requests WHERE user_id = ? AND status IN ('PENDING','RUNNING') LIMIT 1",
            Integer.class, user.get().getId());
        if (!pending.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Your stats are already being refreshed.", "queued", false));
        }
        jdbcTemplate.update(
            "INSERT INTO deep_dive_requests (id, user_id, scope, status, requested_at) " +
            "VALUES (gen_random_uuid(), ?, 'ALL_SEASONS', 'PENDING', NOW())",
            user.get().getId());
        jdbcTemplate.update(
            "INSERT INTO notifications (id, user_id, type, title, message, status) " +
            "VALUES (gen_random_uuid(), ?, 'DEEP_DIVE', ?, ?, 'ACTIVE') " +
            "ON CONFLICT (user_id, type, status) DO NOTHING",
            user.get().getId(), "Stats are being retrieved",
            "We're pulling together your game history and season stats. This usually takes a few minutes.");
        return ResponseEntity.ok(Map.of(
            "message", "We're refreshing your stats — this can take a few minutes.", "queued", true));
    }

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