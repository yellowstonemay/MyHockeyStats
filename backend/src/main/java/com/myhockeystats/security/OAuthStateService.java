package com.myhockeystats.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Stateless, tamper-proof OAuth {@code state} values.
 *
 * The state is a random nonce plus an issue time, signed with an HMAC so it
 * cannot be forged. This replaces the earlier HttpOnly-cookie approach, which
 * broke whenever the browser started the flow on a different host than the
 * registered redirect URI (www vs apex) or blocked/cleared the cookie.
 *
 * Format: {@code base64url(nonce).issuedAtEpochSeconds.base64url(hmac)}
 */
@Component
public class OAuthStateService {

    /** How long a user has to complete the consent screen. */
    private static final long TTL_SECONDS = 600;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateService(@Value("${jwt.secret:dev-secret}") String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            // Match JwtUtil's behaviour for short/dev secrets instead of failing
            // at startup; production always sets a long random JWT_SECRET.
            raw = Arrays.copyOf(raw, 32);
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    /** Mint a fresh state value to send to the provider. */
    public String issue() {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            + "." + Instant.now().getEpochSecond();
        return payload + "." + sign(payload);
    }

    /** True when the state was issued by us, unmodified, and not stale. */
    public boolean isValid(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        String[] parts = state.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        String payload = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            long age = Instant.now().getEpochSecond() - Long.parseLong(parts[1]);
            return age >= 0 && age <= TTL_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign OAuth state", e);
        }
    }
}
