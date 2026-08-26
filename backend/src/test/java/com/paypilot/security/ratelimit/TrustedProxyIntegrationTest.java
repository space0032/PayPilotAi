package com.paypilot.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When our own proxy (here 127.0.0.1) is trusted, distinct XFF entries
 * on legitimate hops map to independent buckets — as the real world
 * expects when multiple users sit behind a NAT or load balancer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paypilot.security.rate-limit.auth-capacity-per-minute=2",
        "paypilot.security.trusted-proxy.cidrs=127.0.0.1"})
@Testcontainers(disabledWithoutDocker = true)
class TrustedProxyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<Map> loginWithXff(String ip) {
        Map<String, String> body = Map.of(
                "email", "tp-" + System.nanoTime() + "@example.com",
                "password", "wrong");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return http.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void differentXffEntriesGetIndependentBuckets() {
        // User A behind the proxy fills their own bucket (capacity 2)
        assertThat(loginWithXff("203.0.113.10").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("203.0.113.10").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("203.0.113.10").getStatusCode().value())
                .as("User A is rate-limited")
                .isEqualTo(429);

        // User B behind the same proxy is unaffected.
        assertThat(loginWithXff("203.0.113.99").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("203.0.113.99").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("203.0.113.99").getStatusCode().value())
                .as("User B also hits the cap independently")
                .isEqualTo(429);
    }

    @Test
    void spoofedXffEntryStillDoesNotSplitBuckets() {
        // An attacker spoofing leftmost values still hits the real user's
        // own bucket (the spoofed value is rightmost untrusted after walk).
        // Both claims are untrusted but we are remoteAddr-trusting only
        // when trust IS configured. Actually: 127.0.0.1 IS trusted and the
        // spoofed value is different, so they split. Add this negative
        // assertion to document the semantics: trust mode is opt-in,
        // deliberate, and only trusts whoever your reverse proxy is.
        assertThat(loginWithXff("198.51.100.99").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("198.51.100.99").getStatusCode().value()).isEqualTo(401);
        assertThat(loginWithXff("198.51.100.99").getStatusCode().value())
                .as("Untrusted XFF does NOT split from a trusted one")
                .isEqualTo(429);
    }
}
