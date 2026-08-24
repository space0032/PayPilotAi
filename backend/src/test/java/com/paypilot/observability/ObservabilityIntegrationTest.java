package com.paypilot.observability;

import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 observability, end to end: health is public, metrics are not,
 * business meters track the money lifecycle, and every audited agent tool
 * call carries the correlation id of the HTTP request that drove it - so
 * a transcript row and its log lines can always be reunited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The Boot test-context customizer disables metric export by default to
// keep test JVMs lean; opt back in - this is THE observability phase.
@AutoConfigureObservability
@Testcontainers(disabledWithoutDocker = true)
class ObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    InventoryRepository inventoryRepository;

    private String newUser(String label) {
        String email = label + "-" + System.nanoTime() + "@example.com";
        return (String) http.postForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "sup3rsafe!"), Map.class)
                .getBody().get("accessToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void health_isPublic_reportsUp() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"UP\"");
        // The correlation filter wraps actuator traffic too.
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void metrics_areNotPublic_butReadableWhenAuthenticated() {
        var anonymous = http.getForEntity("/actuator/metrics", String.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);

        String token = newUser("metrics-reader");
        var authed = http.exchange("/actuator/metrics", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), Map.class);
        assertThat(authed.getStatusCode().value()).isEqualTo(200);
        // Prometheus scrape surface is exposed for the same authenticated
        // audience; it speaks text, so just prove it answers.
        var prometheus = http.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), byte[].class);
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void paymentMeters_trackTheMoneyLifecycle() {
        String token = newUser("metered-buyer");
        Long before = metricCount(token, "paypilot.payments.captured");

        // Full purchase: seed -> cart -> checkout -> initiate -> capture.
        tx.executeWithoutResult(s -> {
            Long pid = productRepository.findBySku("SHOE-NK-DOWN12")
                    .orElseThrow().getId();
            productRepository.setPricePaise(pid, 100_000L);
            inventoryRepository.setAvailable(pid, 10);
        });
        http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId",
                                productRepository.findBySku("SHOE-NK-DOWN12")
                                        .orElseThrow().getId(),
                                "quantity", 1),
                        bearer(token)), Void.class);
        var order = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(null, bearer(token)), Map.class).getBody();
        var payment = http.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", order.get("orderId")),
                        bearer(token)), Map.class).getBody();
        var captured = http.exchange("/api/v1/payments/"
                        + payment.get("paymentId") + "/simulate-capture",
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)),
                String.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);

        Long after = metricCount(token, "paypilot.payments.captured");
        // A meter that never fired may be absent entirely - both states are
        // legal before; after a capture it MUST exist and exceed baseline.
        long baseline = before == null ? 0L : before;
        assertThat(after).isNotNull().isGreaterThan(baseline);
    }

    /** GET /actuator/metrics/{name} -> COUNT value, null if unknown. */
    private Long metricCount(String token, String name) {
        var response = http.exchange("/actuator/metrics/" + name,
                HttpMethod.GET, new HttpEntity<>(null, bearer(token)), Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        java.util.List<?> measurements =
                (java.util.List<?>) response.getBody().get("measurements");
        if (measurements == null || measurements.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Number value = (Number) ((Map<String, Object>) measurements.get(0))
                .get("value");
        return value == null ? null : value.longValue();
    }

    @Test
    void toolCallTrace_carriesTheCorrelationId() {
        String token = newUser("correlated-agent");
        String requestId = "trace-" + System.nanoTime();

        HttpHeaders withId = bearer(token);
        withId.set("X-Request-Id", requestId);
        var session = http.exchange("/api/v1/agent/sessions", HttpMethod.POST,
                new HttpEntity<>(Map.of("goal", "observability probe"), withId),
                Map.class);
        assertThat(session.getStatusCode().value()).isBetween(200, 201);
        Object sessionId = session.getBody().get("sessionId");

        // The run executes scripted planner steps; each audited call must
        // remember WHICH request triggered it.
        http.exchange("/api/v1/agent/sessions/" + sessionId + "/run",
                HttpMethod.POST, new HttpEntity<>(null, withId), Map.class);

        Integer stamped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_tool_calls "
                + "WHERE session_id = ? AND correlation_id = ?",
                Integer.class, Long.valueOf(sessionId.toString()), requestId);
        assertThat(stamped).isGreaterThanOrEqualTo(1);
    }
}

