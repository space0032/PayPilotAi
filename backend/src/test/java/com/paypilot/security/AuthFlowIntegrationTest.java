package com.paypilot.security;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end auth pipeline against real PostgreSQL:
 * register → login → /me → refresh rotation → theft detection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    private static String accessToken;
    private static String refreshToken;

    @Test
    @Order(1)
    void register_returnsTokens() {
        ResponseEntity<Map> response = post("/api/v1/auth/register",
                Map.of("email", "pilot@example.com", "password", "sup3rsafe!"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        accessToken = (String) response.getBody().get("accessToken");
        refreshToken = (String) response.getBody().get("refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(response.getBody().get("email")).isEqualTo("pilot@example.com");
    }

    @Test
    @Order(2)
    void duplicateEmail_caseInsensitive_isRejected() {
        ResponseEntity<Map> response = post("/api/v1/auth/register",
                Map.of("email", "PILOT@example.com", "password", "whatever123"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("code")).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void me_withoutToken_isUnauthenticated() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/me", Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void login_withWrongPassword_failsGenerically() {
        ResponseEntity<Map> response = post("/api/v1/auth/login",
                Map.of("email", "pilot@example.com", "password", "wrong-password"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void login_thenMe_roundTrips() {
        ResponseEntity<Map> login = post("/api/v1/auth/login",
                Map.of("email", "pilot@example.com", "password", "sup3rsafe!"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> me = getWithToken((String) login.getBody().get("accessToken"),
                "/api/v1/me");

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().get("email")).isEqualTo("pilot@example.com");
        assertThat(me.getBody().get("role")).isEqualTo("USER");
    }

    @Test
    @Order(5)
    void refreshRotation_reuseRevokesWholeFamily() {
        // Generation 1 -> 2
        ResponseEntity<Map> first = post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        String gen2 = (String) first.getBody().get("refreshToken");

        // Replay of generation 1: must be detected and nuke the family.
        ResponseEntity<Map> replay = post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
        assertThat(replay.getBody().get("code")).isEqualTo("REFRESH_REUSE_DETECTED");

        // Even generation 2 is now dead - stolen-token tripwire holds.
        ResponseEntity<Map> after = post("/api/v1/auth/refresh", Map.of("refreshToken", gen2));
        assertThat(after.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void malformedRegister_isValidationError() {
        ResponseEntity<Map> response = post("/api/v1/auth/register",
                Map.of("email", "not-an-email", "password", "x"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    private ResponseEntity<Map> post(String url, Object body) {
        return http.postForEntity(url, body, Map.class);
    }

    private ResponseEntity<Map> getWithToken(String token, String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
