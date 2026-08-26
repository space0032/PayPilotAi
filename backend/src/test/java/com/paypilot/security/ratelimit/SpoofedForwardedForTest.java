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
 * The default is "nobody is trusted": X-Forwarded-For cannot rotate
 * buckets. An attacker spinning five hundred XFF claims shares the
 * same bucket as if they said nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "paypilot.security.rate-limit.auth-capacity-per-minute=7")
@Testcontainers(disabledWithoutDocker = true)
class SpoofedForwardedForTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void rotatingSpoofedXffHeaders_shareOneBucket() {
        Map<String, String> body = Map.of(
                "email", "spoof-" + System.nanoTime() + "@example.com",
                "password", "wrong");

        // Each attempt uses a different fake XFF claim; all are untrusted
        // and should collapse into the same rate-limit bucket.
        for (int i = 0; i < 7; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", "198.51.100." + (10 + i));
            ResponseEntity<Map> attempt =
                    http.exchange("/api/v1/auth/login",
                            HttpMethod.POST,
                            new HttpEntity<>(body, headers), Map.class);
            assertThat(attempt.getStatusCode().value())
                    .as("attempt %d should pass", i + 1)
                    .isEqualTo(401);
        }

        // Eighth hit from yet another XFF claim must be blocked.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "198.51.100.200");
        ResponseEntity<Map> blocked =
                http.exchange("/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers), Map.class);
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
    }
}
