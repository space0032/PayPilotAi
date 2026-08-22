package com.paypilot.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brute-force front door: with capacity 3/min, the 4th auth request from the
 * same IP must be rejected before touching credentials. Boots a dedicated
 * context so its bucket map starts empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "paypilot.security.rate-limit.auth-capacity-per-minute=3")
class RateLimitIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private final String unique = String.valueOf(System.nanoTime());

    @Test
    void fourthAuthRequestInsideWindow_isRateLimited() {
        Map<String, String> body = Map.of(
                "email", "ratelimit-" + unique + "@example.com",
                "password", "wrong-password");

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> response =
                    http.postForEntity("/api/v1/auth/login", body, Map.class);
            assertThat(response.getStatusCode().value())
                    .as("attempt %d should pass the limiter", i + 1)
                    .isEqualTo(401);
        }

        ResponseEntity<Map> blocked =
                http.postForEntity("/api/v1/auth/login", body, Map.class);

        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(blocked.getBody().get("code")).isEqualTo("RATE_LIMITED");
    }
}
