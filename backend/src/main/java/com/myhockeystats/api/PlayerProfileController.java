package com.myhockeystats.api;

import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.model.User;
import com.myhockeystats.model.UserPlayer;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.DeepDiveService;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.integration.GameHistoryLookupService;
import com.myhockeystats.service.integration.GameStatOverrideService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Player profile management.
 *
 * A login can manage several players, and a player can be attached to several
 * logins. Endpoints that operate on one player authorise against the
 * user_players link table; "me" endpoints refer to the login's primary player.
 */
@RestController
@RequestMapping("/api/players")
public class PlayerProfileController {
    private static final Pattern BIRTH_MONTH_YEAR_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])/\\d{4}$");
    private static final DateTimeFormatter BIRTH_MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MM/yyyy");

    private final PlayerProfileService playerProfileService;
    private final DeepDiveService deepDiveService;
    private final GameStatOverrideService gameStatOverrideService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public PlayerProfileController(PlayerProfileService playerProfileService,
                                   DeepDiveService deepDiveService,
                                   GameStatOverrideService gameStatOverrideService,
                                   JwtUtil jwtUtil,
                                   UserRepository userRepository) {
        this.playerProfileService = playerProfileService;
        this.deepDiveService = deepDiveService;
        this.gameStatOverrideService = gameStatOverrideService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    record CreateProfileRequest(String fullName, String birthMonthYear, String location,
                                String position, String relation) {}
    record UpdateProfileRequest(String fullName, String birthMonthYear, String location,
                                String position, String photoUrl) {}
    record LinkProfileRequest(Long playerId, String relation) {}
    record GameStatRequest(String source, Integer seasonYear, String gameId,
                           Integer goals, Integer assists, Integer pim) {}
    record OwnerRequest(Long userId, String email) {}
    record EditorRequest(Long userId, String email, Boolean canEdit) {}
    record ProfileResponse(Long id, String fullName, String birthMonthYear, String location,
                           String position, String photoUrl, String relation, Boolean isPrimary,
                           Long ownerUserId, String ownerEmail, Boolean canEdit) {}

    // ── collection ─────────────────────────────────────────────────────────

    /** GET /api/players — every player attached to the signed-in login. */
    @GetMapping
    public ResponseEntity<?> listMyPlayers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<PlayerProfile> players = playerProfileService.listPlayersForUser(user.get().getId());
        Optional<PlayerProfile> primary = playerProfileService.getPrimaryPlayerForUser(user.get().getId());

        List<ProfileResponse> responses = new ArrayList<>();
        for (PlayerProfile player : players) {
            boolean isPrimary = primary.isPresent() && primary.get().getId().equals(player.getId());
            responses.add(toResponse(player, null, isPrimary, user.get()));
        }
        return ResponseEntity.ok(Map.of("players", responses));
    }

    /** POST /api/players — create a player and attach it to the signed-in login. */
    @PostMapping
    public ResponseEntity<?> createPlayer(
            @RequestBody CreateProfileRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        try {
            if (req.fullName() == null || req.fullName().isBlank()) {
                throw new IllegalArgumentException("Player name is required");
            }
            LocalDate birthdate = parseBirthMonthYear(req.birthMonthYear());
            PlayerProfile created = playerProfileService.createPlayer(
                user.get(), req.fullName().trim(), birthdate, req.location(), req.position(),
                req.relation());

            // Newly added players need their career data pulled in.
            DeepDiveService.QueueResult queue = deepDiveService.enqueueForPlayer(created.getId(), user.get().getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "player", toResponse(created, req.relation(), true, user.get()),
                "deepDiveQueued", queue.queued(),
                "message", queue.message()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/players/link — attach an EXISTING player to the signed-in login.
     * This is what lets a second parent / guardian share the same player.
     */
    @PostMapping("/link")
    public ResponseEntity<?> linkPlayer(
            @RequestBody LinkProfileRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (req == null || req.playerId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "playerId is required"));
        }
        Optional<PlayerProfile> player = playerProfileService.getProfileById(req.playerId());
        if (player.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Player not found"));
        }
        boolean alreadyLinked = playerProfileService.isLinked(user.get().getId(), req.playerId());
        UserPlayer link = playerProfileService.linkPlayer(user.get(), player.get(), req.relation());

        // A player shared with a second login may never have been deep-dived.
        DeepDiveService.QueueResult queue = alreadyLinked
            ? new DeepDiveService.QueueResult(false, "This player is already on your account.")
            : deepDiveService.enqueueForPlayer(req.playerId(), user.get().getId());

        return ResponseEntity.ok(Map.of(
            "player", toResponse(player.get(), link.getRelation(), false, user.get()),
            "alreadyLinked", alreadyLinked,
            "deepDiveQueued", queue.queued(),
            "message", queue.message()));
    }

    // ── single player ──────────────────────────────────────────────────────

    /** GET /api/players/{playerId} — requires the player to be on your account. */
    @GetMapping("/{playerId}")
    public ResponseEntity<?> getPlayer(
            @PathVariable Long playerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.isLinked(user.get().getId(), playerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "This player is not on your account"));
        }
        return playerProfileService.getProfileById(playerId)
            .<ResponseEntity<?>>map(p -> ResponseEntity.ok(
                Map.of("player", toResponse(p, null, false, user.get()))))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Player not found")));
    }

    /** PUT /api/players/{playerId} — update a player on your account. */
    @PutMapping("/{playerId}")
    public ResponseEntity<?> updatePlayer(
            @PathVariable Long playerId,
            @RequestBody UpdateProfileRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.isLinked(user.get().getId(), playerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "This player is not on your account"));
        }
        if (!playerProfileService.canEditPlayer(user.get(), playerId)) {
            return ResponseEntity.status(403)
                .body(Map.of("error", "This player is read-only for your account"));
        }
        Optional<PlayerProfile> existing = playerProfileService.getProfileById(playerId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Player not found"));
        }
        try {
            LocalDate birthdate = (req.birthMonthYear() == null || req.birthMonthYear().isBlank())
                ? null
                : parseBirthMonthYear(req.birthMonthYear());
            PlayerProfile updated = playerProfileService.updateProfile(
                existing.get(), req.fullName(), birthdate, req.location(), req.position(), req.photoUrl());
            return ResponseEntity.ok(Map.of("player", toResponse(updated, null, false, user.get())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** DELETE /api/players/{playerId}/link — remove the player from your account. */
    @DeleteMapping("/{playerId}/link")
    public ResponseEntity<?> unlinkPlayer(
            @PathVariable Long playerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        boolean removed = playerProfileService.unlinkPlayer(user.get().getId(), playerId);
        if (!removed) {
            return ResponseEntity.status(404).body(Map.of("error", "This player is not on your account"));
        }
        return ResponseEntity.ok(Map.of("message", "Player removed from your account."));
    }

    /** POST /api/players/{playerId}/primary — make this the default player. */
    @PostMapping("/{playerId}/primary")
    public ResponseEntity<?> setPrimary(
            @PathVariable Long playerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.isLinked(user.get().getId(), playerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "This player is not on your account"));
        }
        playerProfileService.setPrimaryPlayer(user.get().getId(), playerId);
        return ResponseEntity.ok(Map.of("message", "Default player updated."));
    }

    /** POST /api/players/{playerId}/deep-dive — refresh one player (deduped). */
    @PostMapping("/{playerId}/deep-dive")
    public ResponseEntity<?> requestDeepDiveForPlayer(
            @PathVariable Long playerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.isLinked(user.get().getId(), playerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "This player is not on your account"));
        }
        DeepDiveService.QueueResult result = deepDiveService.enqueueForPlayer(playerId, user.get().getId());
        return ResponseEntity.ok(Map.of("queued", result.queued(), "message", result.message()));
    }

    // ── "me" compatibility endpoints (primary player) ───────────────────────

    /** GET /api/players/me — the signed-in login's primary player. */
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        Optional<PlayerProfile> player = playerProfileService.getPrimaryPlayerForUser(user.get().getId());
        if (player.isEmpty()) {
            return ResponseEntity.ok(Map.of("profile", (Object) null));
        }
        return ResponseEntity.ok(Map.of("profile", toResponse(player.get(), UserPlayer.RELATION_SELF, true, user.get())));
    }

    /** PUT /api/players/me — create-or-update the primary player. */
    @PutMapping("/me")
    public ResponseEntity<?> updateMyProfile(
            @RequestBody UpdateProfileRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        try {
            LocalDate birthdate = (req.birthMonthYear() == null || req.birthMonthYear().isBlank())
                ? null
                : parseBirthMonthYear(req.birthMonthYear());

            Optional<PlayerProfile> existing =
                playerProfileService.getPrimaryPlayerForUser(user.get().getId());
            if (existing.isEmpty()) {
                if (birthdate == null) {
                    throw new IllegalArgumentException(
                        "Birth month/year is required in MM/YYYY format");
                }
                PlayerProfile created = playerProfileService.createPlayer(
                    user.get(), req.fullName(), birthdate, req.location(), req.position(), null);
                deepDiveService.enqueueForPlayer(created.getId(), user.get().getId());
                return ResponseEntity.ok(Map.of("profile", toResponse(created, UserPlayer.RELATION_SELF, true, user.get())));
            }

            PlayerProfile updated = playerProfileService.updateProfile(
                existing.get(), req.fullName(), birthdate, req.location(), req.position(), req.photoUrl());
            return ResponseEntity.ok(Map.of("profile", toResponse(updated, null, true, user.get())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/players/me/deep-dive — refresh every player on this login. */
    @PostMapping("/me/deep-dive")
    public ResponseEntity<?> requestDeepDive(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        DeepDiveService.QueueResult result = deepDiveService.enqueueForUser(user.get().getId());
        return ResponseEntity.ok(Map.of("queued", result.queued(), "message", result.message()));
    }

    // ── manual stats + ownership ───────────────────────────────────────────

    /**
     * POST /api/players/{playerId}/game-stats — enter or correct the G/A/PIM of a
     * single game. The value is stored next to the scraped one, so a re-import of
     * the source never overwrites it. Requires edit rights on the player.
     */
    @PostMapping("/{playerId}/game-stats")
    public ResponseEntity<?> saveGameStats(
            @PathVariable Long playerId,
            @RequestBody GameStatRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.canEditPlayer(user.get(), playerId)) {
            return ResponseEntity.status(403)
                .body(Map.of("error", "You do not have edit rights for this player"));
        }
        ResponseEntity<?> invalid = statRequestError(req, true);
        if (invalid != null) {
            return invalid;
        }
        gameStatOverrideService.save(
            playerId,
            req.source().trim().toUpperCase(Locale.ROOT),
            req.seasonYear(),
            req.gameId().trim(),
            req.goals(), req.assists(), req.pim(),
            user.get().getId());
        return ResponseEntity.ok(Map.of("message", "Game stats saved."));
    }

    /** POST /api/players/{playerId}/game-stats/reset — fall back to the scraped value. */
    @PostMapping("/{playerId}/game-stats/reset")
    public ResponseEntity<?> resetGameStats(
            @PathVariable Long playerId,
            @RequestBody GameStatRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.canEditPlayer(user.get(), playerId)) {
            return ResponseEntity.status(403)
                .body(Map.of("error", "You do not have edit rights for this player"));
        }
        ResponseEntity<?> invalid = statRequestError(req, false);
        if (invalid != null) {
            return invalid;
        }
        boolean removed = gameStatOverrideService.delete(
            playerId,
            req.source().trim().toUpperCase(Locale.ROOT),
            req.seasonYear(),
            req.gameId().trim());
        return ResponseEntity.ok(Map.of(
            "reset", removed,
            "message", removed ? "Manual stats cleared." : "This game had no manual stats."));
    }

    /**
     * POST /api/players/{playerId}/owner — admin only. Names the login that owns
     * the player (id or email) and attaches it if it was not attached yet.
     */
    @PostMapping("/{playerId}/owner")
    public ResponseEntity<?> setOwner(
            @PathVariable Long playerId,
            @RequestBody OwnerRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!user.get().isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        Optional<User> target = resolveTargetUser(req.userId(), req.email());
        if (target.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No account found for that email"));
        }
        try {
            PlayerProfile updated = playerProfileService.transferOwnership(target.get(), playerId);
            return ResponseEntity.ok(Map.of(
                "message", "Owner set to " + target.get().getEmail() + ".",
                "player", toResponse(updated, null, false, user.get())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/players/{playerId}/editors — owner or admin. Grants (or revokes)
     * edit rights for a login that is already attached to the player.
     */
    @PostMapping("/{playerId}/editors")
    public ResponseEntity<?> setEditor(
            @PathVariable Long playerId,
            @RequestBody EditorRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!playerProfileService.isOwnerOrAdmin(user.get(), playerId)) {
            return ResponseEntity.status(403)
                .body(Map.of("error", "Only the owner can change edit rights"));
        }
        Optional<User> target = resolveTargetUser(req.userId(), req.email());
        if (target.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No account found for that email"));
        }
        boolean canEdit = req.canEdit() == null || req.canEdit();
        if (!playerProfileService.setEditRights(target.get().getId(), playerId, canEdit)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "That account is not attached to this player yet — add the player to it first."));
        }
        return ResponseEntity.ok(Map.of(
            "message", canEdit ? "Edit rights granted." : "Edit rights removed."));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Shared shape check for the manual-stat endpoints. Returns the error
     * response, or null when the request can be used.
     */
    private ResponseEntity<?> statRequestError(GameStatRequest req, boolean requireValues) {
        if (req.source() == null || !GameHistoryLookupService.isKnownSource(req.source())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown source"));
        }
        if (req.seasonYear() == null || req.gameId() == null || req.gameId().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "seasonYear and gameId are required"));
        }
        if (!requireValues) {
            return null;
        }
        if (req.goals() == null && req.assists() == null && req.pim() == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Enter at least one of goals, assists or pim"));
        }
        for (Integer value : new Integer[] {req.goals(), req.assists(), req.pim()}) {
            if (value != null && (value < 0 || value > 99)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Stat values must be between 0 and 99"));
            }
        }
        return null;
    }

    /** The login named by an ownership/editor request, by id or by email. */
    private Optional<User> resolveTargetUser(Long userId, String email) {
        if (userId != null) {
            return userRepository.findById(userId);
        }
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT));
    }

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        if (email == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
    }

    private ProfileResponse toResponse(PlayerProfile p, String relation, boolean isPrimary, User viewer) {
        return new ProfileResponse(
            p.getId(), p.getFullName(), formatBirthMonthYear(p.getBirthdate()),
            p.getLocation(), p.getPosition(), p.getPhotoUrl(), relation, isPrimary,
            p.getOwnerUserId(), emailOf(p.getOwnerUserId()),
            viewer != null && playerProfileService.canEditPlayer(viewer, p.getId()));
    }

    private String emailOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getEmail).orElse(null);
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
        return birthdate == null ? null : birthdate.format(BIRTH_MONTH_YEAR_FORMATTER);
    }
}
