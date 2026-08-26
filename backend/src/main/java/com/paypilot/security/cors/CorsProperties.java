package com.paypilot.security.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "paypilot.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
