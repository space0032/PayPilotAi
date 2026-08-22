package com.paypilot.security.jwt;

import com.paypilot.security.AuthenticatedUser;
import com.paypilot.security.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and validates HS256 access tokens.
 *
 * Secret policy:
 *   - JWT_SECRET set            -> used as-is (minimum 32 characters).
 *   - blank + 'local' profile   -> ephemeral random key, tokens survive only
 *                                  until restart; loud warning is logged.
 *   - blank + other profiles    -> fail fast at startup. Production must not
 *                                  silently run with throwaway credentials.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_CHARS = 32;
    private static final String ISSUER = "paypilot";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(JwtProperties properties, Environment environment) {
        this.accessTtl = Duration.ofMinutes(properties.accessTokenTtlMinutes());
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            if (isLocalProfile(environment)) {
                secret = randomSecret();
                log.warn("JWT_SECRET not set - using an EPHEMERAL signing key. "
                        + "All sessions become invalid on restart. Set JWT_SECRET for stable tokens.");
            } else {
                throw new IllegalStateException(
                        "JWT_SECRET is required outside the local profile");
            }
        }
        if (secret.length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_CHARS + " characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.userId()))
                .claim("email", user.email())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @return the principal for a valid token, or null when the token is
     *         absent/malformed/expired - callers then treat it as anonymous.
     */
    public AuthenticatedUser parse(String jwt) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            return new AuthenticatedUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("email", String.class),
                    Role.valueOf(claims.get("role", String.class)).name());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTtl.toSeconds();
    }

    private boolean isLocalProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equals(profile)) {
                return true;
            }
        }
        // No explicit profile: spring.profiles.default makes us local anyway.
        return environment.getActiveProfiles().length == 0;
    }

    private String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
