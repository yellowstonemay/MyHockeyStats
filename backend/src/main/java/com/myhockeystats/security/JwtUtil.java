package com.myhockeystats.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {
    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final String DEV_FALLBACK = "dev-secret-dev-secret-dev-secret-dev";

    private final SecretKey key;
    private final long expirationMs = 1000L * 60 * 60 * 24; // 24h

    public JwtUtil(@Value("${jwt.secret:dev-secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.equals("dev-secret")) {
            // For dev only. In production a real JWT_SECRET must be set, otherwise
            // anyone who reads this source can forge a token for any account.
            log.error("================================================================");
            log.error(" JWT_SECRET is NOT set - using the insecure development key.");
            log.error(" Tokens are forgeable. Set JWT_SECRET before exposing this");
            log.error(" service to the internet.");
            log.error("================================================================");
            this.key = Keys.hmacShaKeyFor(DEV_FALLBACK.getBytes());
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes());
        }
    }

    public String generateToken(String subject) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
