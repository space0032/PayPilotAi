package com.paypilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The autonomous agent journey end to end: a goal becomes audited tool
 * calls, the consent ladder gates spending, the per-purchase cap refuses
 * overspend BEFORE any mutation, and every session is owner-scoped
 * evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties =
        "paypilot.security.rate-limit.auth-capacity-per-minute=100")
class AgentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SKU = "SHOE-NK-DOWN12";
    /** Default paypilot.agent.max-spend-paise (Rs 10,000). */
    private static final long CAP_PAISE = 1_000_000L;

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

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

    private Long pid() {
        return jdbc.queryForObject(
                "SELECT id FROM products WHERE sku = ?", Long.class, SKU);
    }

    private record Inv(int available, int reserved) {
    }

    private Inv invOf(Long productId) {
        return jdbc.queryForObject(
                "SELECT available, reserved FROM inventory WHERE product_id = ?",
                (rs, n) -> new Inv(rs.getInt(1), rs.getInt(2)), productId);
    }

    /**
     * Make the shared fixture product uniquely searchable and priced.
     * Prices above the cap exercise the refusal path without needing a
     * second Spring context.
     */
    private void seed(String uniqueTitle, long unitPaise, int available) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE products SET title = ?, price_paise = ? WHERE id = ?",
                    uniqueTitle, unitPaise, pid());
            jdbc.update(
                    "UPDATE inventory SET available = ?, reserved = 0 "
                            + "WHERE product_id = ?", available, pid());
        });
    }

    private Map<String, Object> startSession(String token, String goal) {
        String body = http.exchange("/api/v1/agent/sessions", HttpMethod.POST,
                new HttpEntity<>(Map.of("goal", goal), bearer(token)),
                String.class).getBody();
        assertThat(body).isNotNull();
        return json(body);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callsOf(Map<String, Object> transcript) {
        return (List<Map<String, Object>>) transcript.get("toolCalls");
    }

    @Test
    void scripted_session_buys_through_consent_gate_and_audits_every_step() {
        String token = newUser("agent-happy");
        String title = "Agentic Demo Shoe " + System.nanoTime();
        seed(title, 300_000L, 10); // Rs 300 - comfortably under the cap
        Long productId = pid();
        Inv baseline = invOf(productId);

        Map<String, Object> created = startSession(token, title);
        assertThat(created.get("consentState")).isEqualTo("CONSUMED");
        assertThat(((Number) created.get("reservedSpend")).doubleValue())
                .isEqualTo(3000.00);
        var calls = callsOf(created);
        assertThat(calls).hasSize(8);
        assertThat(calls).allSatisfy(c -> assertThat(c.get("status")).isEqualTo("OK"));
        assertThat(calls.stream().map(c -> c.get("tool"))).containsExactly(
                "search_products", "add_to_cart", "checkout",
                "request_purchase_consent", "confirm_purchase_consent",
                "initiate_payment", "confirm_mock_payment", "get_order_status");

        // The conversation log records both sides of the consent exchange.
        var chat = (List<Map<String, Object>>) created.get("messages");
        assertThat(chat.get(0).get("role")).isEqualTo("USER");
        assertThat(chat).anySatisfy(m -> {
            assertThat(m.get("role")).isEqualTo("AGENT");
            assertThat((String) m.get("content")).contains("approval");
        });
        assertThat(chat).anySatisfy(m ->
                assertThat(m.get("role")).isEqualTo("SYSTEM"));

        // The journey really bought the thing via the owner-scoped API.
        long orderId = ((Number) ((Map<String, Object>)
                calls.get(2).get("resultSummary")).get("orderId")).longValue();
        Map<String, Object> order = json(http.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getBody());
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        assertThat(invOf(productId)).isEqualTo(
                new Inv(baseline.available() - 1, baseline.reserved()));

        // Spend ledger row exists with the session linkage payments lack.
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_events WHERE session_id = ? "
                + "AND type = 'SPEND_RESERVED'",
                Integer.class, ((Number) created.get("sessionId")).longValue());
        assertThat(ledgerRows).isEqualTo(1);
    }

    @Test
    void overCap_refused_before_any_mutation_and_never_asks_consent() {
        String token = newUser("agent-broke");
        String title = "Agentic Luxury Shoe " + System.nanoTime();
        seed(title, CAP_PAISE + 500_000L, 3); // Rs 15k > Rs 10k cap
        Long productId = pid();
        Inv baseline = invOf(productId);

        Map<String, Object> created = startSession(token, title);

        // The guardrail refused; consent was never even requested.
        assertThat(created.get("consentState")).isEqualTo("NONE");
        assertThat(((Number) created.get("reservedSpend")).doubleValue())
                .isEqualTo(0.00);
        var calls = callsOf(created);
        Map<String, Object> last = calls.get(calls.size() - 1);
        assertThat(last.get("tool")).isEqualTo("checkout");
        assertThat(last.get("status")).isEqualTo("REJECTED");
        assertThat(last.get("error")).isEqualTo("SPEND_CAP_EXCEEDED");

        // Nothing moved: no order exists for this user, no stock touched.
        Map<String, Object> ordersPage = json(http.exchange(
                "/api/v1/orders", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getBody());
        assertThat(((Number) ordersPage.get("totalElements")).longValue())
                .isEqualTo(0);
        assertThat(invOf(productId)).isEqualTo(new Inv(baseline.available(), 0));
    }

    @Test
    void transcripts_are_owner_scoped() {
        String owner = newUser("agent-owner");
        String intruder = newUser("agent-intruder");
        seed("Agentic Owned Shoe " + System.nanoTime(), 100_000L, 5);

        // Under-cap price but empty search term match is avoided by using
        // an exact title; this session will complete its plan or fail -
        // either way it belongs to the owner alone. Force a clean failure
        // with an over-cap product so no purchase actually happens.
        seed("Agentic Owned Shoe " + System.nanoTime(), CAP_PAISE + 1, 5);
        Map<String, Object> created = startSession(owner,
                "\"" + "no-such-product-" + System.nanoTime() + "\"");
        long sessionId = ((Number) created.get("sessionId")).longValue();

        var foreign = http.exchange("/api/v1/agent/sessions/" + sessionId,
                HttpMethod.GET, new HttpEntity<>(bearer(intruder)), String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void unauthenticated_start_is_rejected() {
        var response = http.postForEntity("/api/v1/agent/sessions",
                Map.of("goal", "buy something"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String body) {
        try {
            return new ObjectMapper().readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Bad JSON: " + body, e);
        }
    }
}
