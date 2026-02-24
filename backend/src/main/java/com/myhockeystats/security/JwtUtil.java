package com.myhockeystats.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expirationMs = 1000L * 60 * 60 * 24; // 24h

    public JwtUtil(@Value("${jwt.secret:dev-secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.equals("dev-secret")) {
            // For dev only: generate key from default
            this.key = Keys.hmacShaKeyFor("dev-secret-dev-secret-dev-secret-dev".getBytes());
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
}
