package com.paypilot.security.refresh;

import com.paypilot.common.error.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh-token lifecycle: issue, rotate, revoke.
 *
 * Rotation contract: a refresh token is single-use. Presenting a token that
 * was already rotated is treated as a theft signal and revokes every session
 * of that user (token-family defense).
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final Duration refreshTtl;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository,
                               Clock clock,
                               RefreshTokenProperties properties,
                               TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.clock = clock;
        this.refreshTtl = Duration.ofDays(properties.ttlDays());
        this.transactionTemplate = transactionTemplate;
    }

    /** Creates a new session for the user; returns the raw token (shown once). */
    @Transactional
    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, sha256(raw), clock.instant().plus(refreshTtl)));
        return raw;
    }

    /**
     * Validates the presented token and rotates it.
     *
     * Deliberately NOT one big @Transactional: the reuse branch must COMMIT
     * the family-wide revocation before throwing - inside a single
     * transaction the exception would roll the revocation back and leave
     * stolen sessions alive. The revocation therefore runs in its own
     * committed transaction via TransactionTemplate.
     *
     * @return the userId the session belongs to
     * @throws UnauthorizedException on unknown/expired tokens or reuse detection
     */
    public Long rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("REFRESH_INVALID", "Refresh token missing");
        }
        String hash = sha256(rawToken);
        RefreshToken stored = repository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("REFRESH_INVALID",
                        "Refresh token is invalid"));

        if (stored.getRevokedAt() != null) {
            // A revoked token being replayed means it leaked - kill the family.
            long userId = stored.getUserId();
            transactionTemplate.executeWithoutResult(status ->
                    repository.revokeAllForUser(userId, clock.instant()));
            throw new UnauthorizedException("REFRESH_REUSE_DETECTED",
                    "Refresh token was already used; all sessions revoked");
        }
        if (stored.getExpiresAt().isBefore(clock.instant())) {
            throw new UnauthorizedException("REFRESH_EXPIRED", "Refresh token expired");
        }

        stored.revoke(clock.instant());
        repository.save(stored);
        return stored.getUserId();
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    public long ttlSeconds() {
        return refreshTtl.toSeconds();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
