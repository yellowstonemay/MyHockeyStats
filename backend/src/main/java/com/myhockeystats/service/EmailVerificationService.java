package com.myhockeystats.service;

import com.myhockeystats.config.AuthProperties;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Issues and redeems single-use emailed tokens.
 *
 * Two purposes share one table (see the {@code purpose} column):
 *   - {@code SIGNUP}         — confirm a new email address
 *   - {@code PASSWORD_RESET} — let someone set a new password
 *
 * Only the SHA-256 hash of a token is stored, so a database leak cannot be
 * replayed against either endpoint.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final String PURPOSE_SIGNUP = "SIGNUP";
    private static final String PURPOSE_RESET = "PASSWORD_RESET";

    public enum VerifyOutcome { VERIFIED, INVALID, EXPIRED }

    public enum ResetOutcome { RESET, INVALID, EXPIRED, WEAK_PASSWORD }

    public record SendResult(boolean sent, String message) {}

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(JdbcTemplate jdbcTemplate,
                                    UserRepository userRepository,
                                    MailService mailService,
                                    AuthProperties authProperties,
                                    PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
    }

    /** True when SMTP is configured well enough to actually deliver mail. */
    public boolean isMailConfigured() {
        return mailService.isConfigured();
    }

    // ── signup email confirmation ──────────────────────────────────────────

    /**
     * Issue a fresh confirmation token and email the link.
     *
     * @param respectCooldown when true, skip sending if the account was emailed
     *                        within {@code app.auth.resend-cooldown-seconds}
     */
    @Transactional
    public SendResult sendVerification(User user, boolean respectCooldown) {
        if (user == null || user.getEmail() == null) {
            return new SendResult(false, "No account to verify.");
        }
        if (user.isEmailVerified()) {
            return new SendResult(false, "That email address is already confirmed.");
        }
        if (!mailService.isConfigured()) {
            log.error("Signup blocked: mail is not configured, so {} cannot be verified", user.getId());
            return new SendResult(false,
                "We can't send confirmation emails right now. Please try again shortly.");
        }
        Optional<String> tooSoon = cooldownMessage(user.getId(), PURPOSE_SIGNUP, respectCooldown);
        if (tooSoon.isPresent()) {
            return new SendResult(false, tooSoon.get());
        }

        String rawToken = issueToken(user.getId(), PURPOSE_SIGNUP,
            Instant.now().plus(authProperties.getVerificationTtlHours(), ChronoUnit.HOURS));

        boolean sent = mailService.sendVerificationEmail(
            user.getEmail(), user.getFullName(), buildVerifyUrl(rawToken));

        return sent
            ? new SendResult(true, "Check your email — we sent you a link to confirm your address.")
            : new SendResult(false, "We couldn't send the confirmation email. Please try again shortly.");
    }

    /** Redeem a confirmation link and mark the account verified. */
    @Transactional
    public VerifyOutcome verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return VerifyOutcome.INVALID;
        }
        String hash = sha256Hex(rawToken);

        Optional<Long> userId = findLiveToken(hash, PURPOSE_SIGNUP);
        if (userId.isEmpty()) {
            return tokenExists(hash, PURPOSE_SIGNUP) ? VerifyOutcome.EXPIRED : VerifyOutcome.INVALID;
        }

        consumeToken(hash);
        userRepository.findById(userId.get()).ifPresent(user -> {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
            log.info("Email verified for user {}", userId.get());
        });
        return VerifyOutcome.VERIFIED;
    }

    // ── password reset ─────────────────────────────────────────────────────

    /**
     * Issue a reset token and email the link. Always reports success to the
     * caller (see AuthController) so the endpoint cannot enumerate accounts.
     */
    @Transactional
    public SendResult sendPasswordReset(User user, boolean respectCooldown) {
        if (user == null || user.getEmail() == null) {
            return new SendResult(false, "No account to reset.");
        }
        if (!mailService.isConfigured()) {
            log.error("Password reset blocked: mail is not configured (user {})", user.getId());
            return new SendResult(false,
                "We can't send emails right now. Please try again shortly.");
        }
        // A password reset only makes sense for an account that has a password.
        // Social-only accounts sign in through their provider instead.
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            log.info("Password reset skipped for social-only account {}", user.getId());
            return new SendResult(false, "This account signs in with a social provider.");
        }
        Optional<String> tooSoon = cooldownMessage(user.getId(), PURPOSE_RESET, respectCooldown);
        if (tooSoon.isPresent()) {
            return new SendResult(false, tooSoon.get());
        }

        String rawToken = issueToken(user.getId(), PURPOSE_RESET,
            Instant.now().plus(authProperties.getResetTtlMinutes(), ChronoUnit.MINUTES));

        boolean sent = mailService.sendPasswordResetEmail(
            user.getEmail(), user.getFullName(), buildResetUrl(rawToken));

        return sent
            ? new SendResult(true, "If that address has an account, a reset link is on its way.")
            : new SendResult(false, "We couldn't send the reset email. Please try again shortly.");
    }

    /** Whether the link in the reset email is still redeemable. */
    public boolean isResetTokenValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return findLiveToken(sha256Hex(rawToken), PURPOSE_RESET).isPresent();
    }

    /**
     * Set a new password against a reset token and burn the token.
     *
     * Also marks the address verified (clicking the link proves they control
     * the inbox) and consumes any other outstanding reset tokens for the user.
     */
    @Transactional
    public ResetOutcome resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            return ResetOutcome.INVALID;
        }
        if (newPassword == null || newPassword.length() < 8) {
            return ResetOutcome.WEAK_PASSWORD;
        }
        String hash = sha256Hex(rawToken);

        Optional<Long> userId = findLiveToken(hash, PURPOSE_RESET);
        if (userId.isEmpty()) {
            return tokenExists(hash, PURPOSE_RESET) ? ResetOutcome.EXPIRED : ResetOutcome.INVALID;
        }

        consumeToken(hash);
        // Any other outstanding reset links for this account stop working.
        jdbcTemplate.update(
            "UPDATE email_verification_tokens SET consumed_at = NOW() " +
            "WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL",
            userId.get(), PURPOSE_RESET);

        userRepository.findById(userId.get()).ifPresent(user -> {
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
            log.info("Password reset completed for user {}", userId.get());
        });
        return ResetOutcome.RESET;
    }

    // ── urls ───────────────────────────────────────────────────────────────

    public String buildVerifyUrl(String rawToken) {
        return baseUrl() + "/api/auth/verify-email?token=" + rawToken;
    }

    public String buildVerifyResultUrl(VerifyOutcome outcome) {
        String status = switch (outcome) {
            case VERIFIED -> "ok";
            case EXPIRED -> "expired";
            case INVALID -> "invalid";
        };
        return baseUrl() + "/verify-email?status=" + status;
    }

    /** The link in the reset email — goes to the API, which redirects to the SPA. */
    public String buildResetUrl(String rawToken) {
        return baseUrl() + "/api/auth/reset-password?token=" + rawToken;
    }

    /** Where the browser lands so the user can type a new password. */
    public String buildResetFormUrl(String rawToken) {
        return baseUrl() + "/reset-password?token=" + rawToken;
    }

    public String buildResetErrorUrl(String status) {
        return baseUrl() + "/reset-password?status=" + status;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String issueToken(Long userId, String purpose, Instant expiresAt) {
        String rawToken = newRawToken();
        jdbcTemplate.update(
            "INSERT INTO email_verification_tokens (id, user_id, token_hash, purpose, expires_at, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, NOW())",
            userId, sha256Hex(rawToken), purpose, Timestamp.from(expiresAt));
        return rawToken;
    }

    /** Live (unconsumed, unexpired) token for this hash and purpose. */
    private Optional<Long> findLiveToken(String hash, String purpose) {
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT user_id FROM email_verification_tokens " +
            "WHERE token_hash = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > NOW()",
            Long.class, hash, purpose);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** True when the token exists but is expired or already used. */
    private boolean tokenExists(String hash, String purpose) {
        List<Integer> rows = jdbcTemplate.queryForList(
            "SELECT 1 FROM email_verification_tokens WHERE token_hash = ? AND purpose = ? LIMIT 1",
            Integer.class, hash, purpose);
        return !rows.isEmpty();
    }

    private void consumeToken(String hash) {
        jdbcTemplate.update(
            "UPDATE email_verification_tokens SET consumed_at = NOW() " +
            "WHERE token_hash = ? AND consumed_at IS NULL", hash);
    }

    /** When this account was last emailed a token of this kind. */
    private Optional<Instant> lastSentAt(Long userId, String purpose) {
        List<Timestamp> rows = jdbcTemplate.queryForList(
            "SELECT created_at FROM email_verification_tokens " +
            "WHERE user_id = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1",
            Timestamp.class, userId, purpose);
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0).toInstant());
    }

    private Optional<String> cooldownMessage(Long userId, String purpose, boolean respectCooldown) {
        if (!respectCooldown) {
            return Optional.empty();
        }
        Optional<Instant> lastSent = lastSentAt(userId, purpose);
        if (lastSent.isEmpty()) {
            return Optional.empty();
        }
        long waited = ChronoUnit.SECONDS.between(lastSent.get(), Instant.now());
        if (waited >= authProperties.getResendCooldownSeconds()) {
            return Optional.empty();
        }
        long remaining = authProperties.getResendCooldownSeconds() - waited;
        return Optional.of("We just sent an email. Please wait " + remaining + "s and try again.");
    }

    private String baseUrl() {
        String base = authProperties.getPublicBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
