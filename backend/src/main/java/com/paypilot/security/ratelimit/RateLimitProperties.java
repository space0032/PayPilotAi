package com.paypilot.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paypilot.security.rate-limit")
public record RateLimitProperties(long authCapacityPerMinute) {

    public RateLimitProperties {
        if (authCapacityPerMinute <= 0) {
            authCapacityPerMinute = 10;
        }
    }
}
