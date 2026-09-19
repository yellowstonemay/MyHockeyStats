package com.myhockeystats.api;

import com.myhockeystats.config.OAuthProperties;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.security.OAuthStateService;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.UserService;
import com.myhockeystats.service.oauth.OAuthProviderClient;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Social sign-in (Google / Facebook) for the SPA.
 *
 * Flow:
 *   1. GET /api/auth/oauth2/{provider}/start — mint a signed state, 302 to the
 *      provider.
 *   2. Provider redirects to GET /api/auth/oauth2/callback/{provider} (the shape
 *      registered in the Google console). We verify the state, exchange the code
 *      for the profile, and resolve the account.
 *   3. The browser lands on the SPA callback route and trades a one-time code for
 *      the JWT — the JWT itself never appears in a URL.
 *
 * The state is HMAC-signed rather than stored in a cookie, so the flow works
 * regardless of which host (www or apex) the user starts on and is unaffected by
 * browsers that drop cookies.
 *
 * If the provider email already belongs to an email/password account we do NOT
 * attach silently — the SPA is asked for that account's password first.
 */
@RestController
@RequestMapping("/api/auth/oauth2")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    private static final long HANDOFF_TTL_SECONDS = 120;

    private final OAuthProperties oauthProperties;
    private final OAuthProviderClient providerClient;
    private final UserService userService;
    private final PlayerProfileService playerProfileService;
    private final UserRepository userRepository;
    private final OAuthStateService stateService;
    private final JwtUtil jwtUtil;
    private final SecureRandom random = new SecureRandom();

    /** Short-lived, single-use codes swapped for a JWT by the SPA. */
    private final Map<String, Handoff> handoffs = new ConcurrentHashMap<>();

    /** Short-lived, single-use codes for "confirm your password to link" flows. */
    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();

    private record Handoff(String token, String email, boolean isAdmin, boolean hasPlayer, long expiresAt) {}

    private record PendingLink(Long userId, String provider, String providerUserId, String email,
                               String displayName, String avatarUrl, boolean emailVerified,
                               long expiresAt) {}

    public OAuthController(OAuthProperties oauthProperties,
                           OAuthProviderClient providerClient,
                           UserService userService,
                           PlayerProfileService playerProfileService,
                           UserRepository userRepository,
                           OAuthStateService stateService,
                           JwtUtil jwtUtil) {
        this.oauthProperties = oauthProperties;
        this.providerClient = providerClient;
        this.userService = userService;
        this.playerProfileService = playerProfileService;
        this.userRepository = userRepository;
        this.stateService = stateService;
        this.jwtUtil = jwtUtil;
    }

    /** GET /api/auth/oauth2/providers — which buttons the UI should show. */
    @GetMapping("/providers")
    public ResponseEntity<?> providers() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : new String[] {"google", "facebook"}) {
            result.put(id, Map.of(
                "enabled", providerClient.isConfigured(id),
                "label", "google".equals(id) ? "Google" : "Facebook"));
        }
        return ResponseEntity.ok(Map.of("providers", result));
    }

    /** GET /api/auth/oauth2/{provider}/start — begin the consent flow. */
    @GetMapping("/{provider}/start")
    public void start(@PathVariable String provider,
                      HttpServletResponse response) throws java.io.IOException {
        OAuthProperties.Provider config = oauthProperties.provider(provider);
        if (config == null || !config.isConfigured()) {
            redirectWithError(response, provider + " sign-in is not available yet.");
            return;
        }
        response.sendRedirect(providerClient.authorizationUrl(provider, stateService.issue()));
    }

    /**
     * GET /api/auth/oauth2/callback/{provider} — the provider redirects here.
     * The path order matches the redirect URIs registered with the providers.
     */
    @GetMapping("/callback/{provider}")
    public void callback(@PathVariable String provider,
                         @RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error_description", required = false) String errorDescription,
                         @RequestParam(value = "error", required = false) String error,
                         HttpServletResponse response) throws java.io.IOException {
        boolean stateOk = stateService.isValid(state);
        log.info("OAuth callback: provider={}, hasCode={}, error={}, stateOk={}",
            provider, code != null && !code.isBlank(), error, stateOk);

        if (error != null) {
            redirectWithError(response, errorDescription != null ? errorDescription : error);
            return;
        }
        if (code == null || code.isBlank()) {
            redirectWithError(response, "Missing authorization code from " + provider + ".");
            return;
        }
        if (!stateOk) {
            redirectWithError(response, "That sign-in link has expired. Please start again.");
            return;
        }

        try {
            OAuthProviderClient.SocialProfile profile = providerClient.exchangeCode(provider, code);
            log.info("OAuth profile resolved: provider={}, emailPresent={}, emailVerified={}",
                profile.provider(), profile.email() != null, profile.emailVerified());

            UserService.ProviderLoginResult result = userService.resolveProviderLogin(
                profile.provider(), profile.providerUserId(), profile.email(),
                profile.displayName(), profile.avatarUrl(), profile.emailVerified());

            switch (result.outcome()) {
                case SIGNED_IN -> {
                    log.info("Social sign-in OK: provider={}, userId={}", provider, result.user().getId());
                    redirectWithToken(response, result.user());
                }
                case LINK_NOT_POSSIBLE -> redirectWithError(response, result.message());
                case LINK_REQUIRES_CONFIRMATION -> {
                    log.info("Social sign-in needs confirmation: provider={}, userId={}",
                        provider, result.user().getId());
                    String linkCode = newPendingLink(new PendingLink(
                        result.user().getId(), profile.provider(), profile.providerUserId(),
                        profile.email(), profile.displayName(), profile.avatarUrl(),
                        profile.emailVerified(),
                        Instant.now().getEpochSecond() + HANDOFF_TTL_SECONDS));
                    response.sendRedirect(oauthProperties.getFrontendCallbackUrl()
                        + "?link=" + linkCode
                        + "&email=" + urlEncode(result.user().getEmail()));
                }
            }
        } catch (Exception ex) {
            // Log the whole chain: the useful detail is often in the cause, not
            // the wrapper. Without this the user just sees a generic message.
            Throwable root = ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.error("Social sign-in failed for provider={}: {}: {} | root cause: {}: {}",
                provider, ex.getClass().getSimpleName(), ex.getMessage(),
                root.getClass().getSimpleName(), root.getMessage(), ex);
            redirectWithError(response, "We couldn't complete " + provider + " sign-in. Please try again.");
        }
    }

    /** POST /api/auth/oauth2/exchange — swap the one-time code for a JWT. */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing code"));
        }
        Handoff handoff = handoffs.remove(code);
        if (handoff == null || handoff.expiresAt() < Instant.now().getEpochSecond()) {
            return ResponseEntity.status(401).body(Map.of("error", "This sign-in link has expired."));
        }
        return ResponseEntity.ok(Map.of(
            "token", handoff.token(),
            "email", handoff.email(),
            "isAdmin", handoff.isAdmin(),
            "hasPlayer", handoff.hasPlayer()));
    }

    /**
     * POST /api/auth/oauth2/complete-link — confirm ownership of an existing
     * email/password account so the social login can be attached to it.
     * Body: { code, password }
     */
    @PostMapping("/complete-link")
    public ResponseEntity<?> completeLink(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        String password = body == null ? null : body.get("password");
        if (code == null || code.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }

        PendingLink pending = pendingLinks.remove(code);
        if (pending == null || pending.expiresAt() < Instant.now().getEpochSecond()) {
            return ResponseEntity.status(401)
                .body(Map.of("error", "This request has expired. Please sign in again."));
        }

        Optional<User> user = userRepository.findById(pending.userId());
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Account not found"));
        }
        if (!userService.verifyPassword(user.get(), password)) {
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect password"));
        }

        User linked = userService.completeProviderLink(
            user.get(), pending.provider(), pending.providerUserId(),
            pending.email(), pending.displayName(), pending.avatarUrl(), pending.emailVerified());
        log.info("Social account linked to existing user {} via {}", linked.getId(), pending.provider());

        return ResponseEntity.ok(Map.of(
            "token", jwtUtil.generateToken(linked.getEmail()),
            "email", linked.getEmail(),
            "isAdmin", linked.isAdmin(),
            "hasPlayer", playerProfileService.getPrimaryPlayerForUser(linked.getId()).isPresent()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void redirectWithToken(HttpServletResponse response, User user) throws java.io.IOException {
        boolean hasPlayer = playerProfileService.getPrimaryPlayerForUser(user.getId()).isPresent();
        String handoffCode = newHandoff(new Handoff(
            jwtUtil.generateToken(user.getEmail()), user.getEmail(), user.isAdmin(),
            hasPlayer, Instant.now().getEpochSecond() + HANDOFF_TTL_SECONDS));
        response.sendRedirect(oauthProperties.getFrontendCallbackUrl() + "?code=" + handoffCode);
    }

    private String newHandoff(Handoff handoff) {
        long now = Instant.now().getEpochSecond();
        handoffs.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        String code = randomToken();
        handoffs.put(code, handoff);
        return code;
    }

    private String newPendingLink(PendingLink pending) {
        long now = Instant.now().getEpochSecond();
        pendingLinks.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        String code = randomToken();
        pendingLinks.put(code, pending);
        return code;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void redirectWithError(HttpServletResponse response, String message) throws java.io.IOException {
        response.sendRedirect(oauthProperties.getFrontendCallbackUrl()
            + "?error=" + urlEncode(Optional.ofNullable(message).orElse("Sign-in failed")));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
