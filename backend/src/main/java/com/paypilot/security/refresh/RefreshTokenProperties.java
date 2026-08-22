package com.paypilot.security.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paypilot.security.refresh-token")
public record RefreshTokenProperties(int ttlDays) {

    public RefreshTokenProperties {
        if (ttlDays <= 0) {
            ttlDays = 7;
        }
    }
}
