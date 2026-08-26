package com.paypilot.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which upstream addresses may speak for a client by setting
 * X-Forwarded-For. Deliberately EMPTY in direct-exposure deployments:
 * an untrusted XFF header must never rotate rate-limit buckets, or the
 * brute-force front door has no door left to guard.
 */
@ConfigurationProperties(prefix = "paypilot.security.trusted-proxy")
public record TrustedProxyProperties(List<String> cidrs) {

    public TrustedProxyProperties {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
    }
}
