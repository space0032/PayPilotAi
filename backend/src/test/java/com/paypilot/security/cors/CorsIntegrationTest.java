package com.paypilot.security.cors;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS policy: preflights from the configured origin are accepted;
 * rogue origins are rejected; real GETs carry the allow-origin header
 * so the browser renders the response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paypilot.security.cors.allowed-origins=http://localhost:5173,https://paypilot.example.com"})
@Testcontainers(disabledWithoutDocker = true)
class CorsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<String> preflight(String origin, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type");
        return http.exchange(path, HttpMethod.OPTIONS,
                new HttpEntity<>(null, headers), String.class);
    }

    @Test
    void preflight_acceptedForConfiguredOrigin() {
        ResponseEntity<String> response =
                preflight("http://localhost:5173", "/api/v1/cart/items");

        assertThat(response.getStatusCode().value()).isIn(200, 204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:5173");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods"))
                .contains("POST");
    }

    @Test
    void preflight_rejectedForUnknownOrigin() {
        ResponseEntity<String> response =
                preflight("https://evil.example", "/api/v1/cart/items");

        String allowed = response.getHeaders().getFirst("Access-Control-Allow-Origin");
        assertThat(allowed == null || !allowed.equals("https://evil.example")).isTrue();
    }

    @Test
    void actualGet_carryAllowOriginHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://paypilot.example.com");
        ResponseEntity<String> response = http.exchange("/api/v1/products?size=1",
                HttpMethod.GET, new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("https://paypilot.example.com");
    }

    @Test
    void disallowedOrigin_cannotSeeProtectedResource() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://attacker.example");
        ResponseEntity<String> response = http.exchange("/api/v1/cart/items",
                HttpMethod.GET, new HttpEntity<>(null, headers), String.class);

        // Auth failure (401) or opaque CORS error; either way no allow header leaks.
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isNull();
    }
}
