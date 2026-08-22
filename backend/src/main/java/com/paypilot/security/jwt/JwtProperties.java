package com.paypilot.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Values are defaulted in application.yml; {@code secret} may be blank there -
 * JwtService decides between ephemeral-local and fail-fast behaviour.
 */
@ConfigurationProperties(prefix = "paypilot.security.jwt")
public record JwtProperties(String secret, int accessTokenTtlMinutes) {

    public JwtProperties {
        if (accessTokenTtlMinutes <= 0) {
            accessTokenTtlMinutes = 15;
        }
    }
}
