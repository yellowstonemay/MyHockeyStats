package com.myhockeystats.api;

import com.myhockeystats.config.AuthProperties;
import com.myhockeystats.model.User;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.EmailVerificationService;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Email/password authentication.
 *
 * Password signup creates an UNVERIFIED account and emails a single-use
 * confirmation link; sign-in is refused until that link is opened. Social
 * sign-in is exempt because the provider already verified the address.
 *
 * Set APP_REQUIRE_EMAIL_VERIFICATION=false to fall back to the old behaviour.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final PlayerProfileService playerProfileService;
    private final EmailVerificationService emailVerificationService;
    private final AuthProperties authProperties;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService,
                          PlayerProfileService playerProfileService,
                          EmailVerificationService emailVerificationService,
                          AuthProperties authProperties,
                          JwtUtil jwtUtil) {
        this.userService = userService;
        this.playerProfileService = playerProfileService;
        this.emailVerificationService = emailVerificationService;
        this.authProperties = authProperties;
        this.jwtUtil = jwtUtil;
    }

    record SignupRequest(String email, String password) {}
    record LoginRequest(String email, String password) {}
    record ResendRequest(String email) {}
    record ForgotPasswordRequest(String email) {}
    record ResetPasswordRequest(String token, String newPassword) {}

    /**
     * POST /api/auth/signup — create the account, then email a confirmation
     * link. No JWT is returned: the address has not been proven yet.
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        try {
            if (req.email() == null || req.email().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
            }
            if (req.password() == null || req.password().length() < 8) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least 8 characters"));
            }
            String email = req.email().trim().toLowerCase();

            if (authProperties.isRequireEmailVerification() && !emailVerificationService.isMailConfigured()) {
                // Fail before creating anything: an account nobody can confirm
                // would be a dead end for the user.
                log.error("Signup rejected: email verification is required but SMTP is not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Sign-up is temporarily unavailable. Please try again shortly."));
            }

            User u = userService.register(email, req.password(), null);

            if (!authProperties.isRequireEmailVerification()) {
                // Escape hatch: verification disabled, behave like before.
                String token = jwtUtil.generateToken(u.getEmail());
                return ResponseEntity.ok(Map.of(
                    "token", token, "email", u.getEmail(), "isAdmin", u.isAdmin(), "hasPlayer", false));
            }

            EmailVerificationService.SendResult send = emailVerificationService.sendVerification(u, false);
            if (!send.sent()) {
                // Roll the account back rather than leave an unusable one.
                log.error("Signup for user {} rolled back: {}", u.getId(), send.message());
                userService.deleteAccount(u.getId());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", send.message()));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "email", u.getEmail(),
                "verificationRequired", true,
                "message", send.message()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/auth/login — refuses unverified accounts with a distinct code
     * so the UI can offer to resend the confirmation email.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        Optional<User> found = userService.findByEmail(
            req.email() == null ? null : req.email().trim().toLowerCase());

        if (found.isEmpty() || !userService.verifyPassword(found.get(), req.password())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        User u = found.get();
        if (authProperties.isRequireEmailVerification() && !u.isEmailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "EMAIL_NOT_VERIFIED",
                "error", "Please confirm your email address before signing in. "
                    + "We can send you a new confirmation link."));
        }

        userService.touchLastLogin(u);
        return ResponseEntity.ok(Map.of(
            "token", jwtUtil.generateToken(u.getEmail()),
            "email", u.getEmail(),
            "isAdmin", u.isAdmin(),
            "hasPlayer", playerProfileService.getPrimaryPlayerForUser(u.getId()).isPresent()));
    }

    /**
     * POST /api/auth/resend-verification — always answers the same way so the
     * endpoint cannot be used to discover which addresses have accounts.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody ResendRequest req) {
        Map<String, Object> generic = Map.of(
            "message", "If that address has an unconfirmed account, a new link is on its way.");

        if (req == null || req.email() == null || req.email().isBlank()) {
            return ResponseEntity.ok(generic);
        }
        Optional<User> user = userService.findByEmail(req.email().trim().toLowerCase());
        if (user.isEmpty() || user.get().isEmailVerified()
            || !authProperties.isRequireEmailVerification()) {
            return ResponseEntity.ok(generic);
        }

        EmailVerificationService.SendResult send =
            emailVerificationService.sendVerification(user.get(), true);
        log.info("Resend verification for user {}: sent={}", user.get().getId(), send.sent());
        return ResponseEntity.ok(generic);
    }

    /**
     * GET /api/auth/verify-email?token=... — the link from the email. Consumes
     * the token and bounces the browser to the SPA result page.
     */
    @GetMapping("/verify-email")
    public void verifyEmail(@RequestParam(value = "token", required = false) String token,
                            HttpServletResponse response) throws IOException {
        EmailVerificationService.VerifyOutcome outcome = emailVerificationService.verify(token);
        response.sendRedirect(emailVerificationService.buildVerifyResultUrl(outcome));
    }

    /**
     * POST /api/auth/forgot-password — email a reset link.
     *
     * Always answers identically so the endpoint cannot be used to discover
     * which addresses have accounts.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        Map<String, Object> generic = Map.of(
            "message", "If that address has an account, we've sent a link to reset the password.");

        if (req == null || req.email() == null || req.email().isBlank()) {
            return ResponseEntity.ok(generic);
        }
        Optional<User> user = userService.findByEmail(req.email().trim().toLowerCase());
        if (user.isEmpty()) {
            log.info("Password reset requested for an unknown address");
            return ResponseEntity.ok(generic);
        }

        EmailVerificationService.SendResult send =
            emailVerificationService.sendPasswordReset(user.get(), true);
        log.info("Password reset requested for user {}: sent={}", user.get().getId(), send.sent());
        return ResponseEntity.ok(generic);
    }

    /**
     * GET /api/auth/reset-password?token=... — the link from the email. Validates
     * the token and hands the user to the SPA form; does NOT consume it.
     */
    @GetMapping("/reset-password")
    public void resetPasswordLink(@RequestParam(value = "token", required = false) String token,
                                  HttpServletResponse response) throws IOException {
        if (emailVerificationService.isResetTokenValid(token)) {
            response.sendRedirect(emailVerificationService.buildResetFormUrl(token));
        } else {
            response.sendRedirect(emailVerificationService.buildResetErrorUrl("invalid"));
        }
    }

    /**
     * POST /api/auth/reset-password — set the new password and burn the token.
     * Body: { token, newPassword }
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest req) {
        if (req == null || req.newPassword() == null || req.newPassword().length() < 8) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Password must be at least 8 characters"));
        }

        EmailVerificationService.ResetOutcome outcome =
            emailVerificationService.resetPassword(req.token(), req.newPassword());

        return switch (outcome) {
            case RESET -> ResponseEntity.ok(Map.of(
                "message", "Your password has been changed. You can sign in now."));
            case WEAK_PASSWORD -> ResponseEntity.badRequest()
                .body(Map.of("error", "Password must be at least 8 characters"));
            case EXPIRED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "That reset link has expired. Please request a new one."));
            case INVALID -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "That reset link is not valid or has already been used. "
                    + "Please request a new one."));
        };
    }
}
