package com.paypilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live LLM planner end to end - against a stub /chat/completions
 * server, so the wire protocol, decision parsing, human-in-the-loop
 * pause/resume and safe failure are all exercised without any vendor
 * account. The model asks; the consent API answers; money moves only
 * across that boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AgentLivePlannerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // ----- stub OpenAI-compatible server --------------------------------

    private static final BlockingQueue<String> REPLIES = new LinkedBlockingQueue<>();
    private static final List<String> AUTH_HEADERS = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    String auth = exchange.getRequestHeaders()
                            .getFirst("Authorization");
                    if (auth != null) {
                        AUTH_HEADERS.add(auth);
                    }
                    exchange.getRequestBody().readAllBytes();
                    String decision = REPLIES.poll(5, TimeUnit.SECONDS);
                    if (decision == null) {
                        send(exchange, 500, "{\"error\":\"stub queue empty\"}");
                        return;
                    }
                    String body = JSON_MAPPER.writeValueAsString(Map.of(
                            "choices", List.of(Map.of(
                                    "message", Map.of(
                                            "role", "assistant",
                                            "content", decision)))));
                    send(exchange, 200, body);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Stub LLM server failed", e);
        }
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Queue what the "model" will answer, one chat call at a time. */
    private static void modelWill(String... decisions) {
        REPLIES.addAll(List.of(decisions));
    }

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("paypilot.security.rate-limit.auth-capacity-per-minute",
                () -> "100");
        registry.add("paypilot.agent.planner", () -> "live");
        registry.add("paypilot.agent.llm.provider",
                () -> "openai-compatible");
        registry.add("paypilot.agent.llm.base-url",
                () -> "http://localhost:" + STUB.getAddress().getPort() + "/v1");
        registry.add("paypilot.agent.llm.api-key", () -> "test-key-123");
        registry.add("paypilot.agent.llm.model", () -> "stub-model");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    // ------------------------------------------------------------------

    /** Decisions the scripted model emits for run 1: browse to consent ask. */
    private void scriptBrowseToConsentAsk(long productId, String term,
                                          long amountPaise) {
        modelWill(
                jsonDecision("search_products", Map.of("term", term)),
                jsonDecision("add_to_cart", Map.of(
                        "productId", productId, "quantity", 1)),
                jsonDecision("checkout", Map.of()),
                jsonDecision("request_purchase_consent",
                        Map.of("amountPaise", amountPaise)),
                jsonDecision("done", Map.of()));
    }

    private static String jsonDecision(String tool, Map<String, Object> args) {
        try {
            return JSON_MAPPER.writeValueAsString(
                    Map.of("tool", tool, "arguments", args));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private static final String SKU = "SHOE-NK-DOWN12";
    private static final long CAP_PAISE = 1_000_000L;

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

    private void seed(String uniqueTitle, long unitPaise, int available) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE products SET title = ?, price_paise = ? WHERE id = ?",
                    uniqueTitle, unitPaise, pid());
            jdbc.update(
                    "UPDATE inventory SET available = ?, reserved = 0 "
                            + "WHERE product_id = ?", available, pid());
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String token, String path,
                                     Map<String, Object> body) {
        String raw = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), String.class).getBody();
        assertThat(raw).isNotNull();
        return parse(raw);
    }

    private Map<String, Object> get(String token, String path) {
        String raw = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getBody();
        assertThat(raw).isNotNull();
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callsOf(Map<String, Object> transcript) {
        return (List<Map<String, Object>>) transcript.get("toolCalls");
    }

    /** The paymentId only exists after initiation - read it from the trace. */
    private long paymentIdOf(String token, long sessionId) {
        return callsOf(get(token, "/api/v1/agent/sessions/" + sessionId))
                .stream()
                .filter(c -> c.get("tool").equals("initiate_payment"))
                .mapToLong(c -> ((Number) ((Map<String, Object>)
                        c.get("resultSummary")).get("paymentId")).longValue())
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        try {
            return JSON_MAPPER.readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Bad JSON: " + body, e);
        }
    }

    // ------------------------------------------------------------------

    @Test
    void live_planner_pauses_for_human_consent_then_completes_purchase() {
        String token = newUser("agent-live");
        String title = "Agentic Live Shoe " + System.nanoTime();
        seed(title, 300_000L, 10); // Rs 3,000 under the cap
        Long productId = pid();
        Inv baseline = invOf(productId);

        // ---- Run 1: the agent shops, asks permission, waits. ----------
        scriptBrowseToConsentAsk(productId, title, 300_000L);
        Map<String, Object> paused = post(token, "/api/v1/agent/sessions",
                Map.of("goal", title));

        assertThat(paused.get("consentState")).isEqualTo("REQUESTED");
        assertThat(((Number) paused.get("reservedSpend")).doubleValue())
                .isEqualTo(0.00);
        var calls = callsOf(paused);
        assertThat(calls.stream().map(c -> c.get("tool"))).containsExactly(
                "search_products", "add_to_cart", "checkout",
                "request_purchase_consent");
        assertThat(calls).allSatisfy(c ->
                assertThat(c.get("status")).isEqualTo("OK"));

        long sessionId = ((Number) paused.get("sessionId")).longValue();
        long orderId = ((Number) ((Map<String, Object>)
                calls.get(2).get("resultSummary")).get("orderId")).longValue();

        // Checkout reserved the unit for the pending order; nothing is
        // paid yet - the reservation belongs to an unanswered consent.
        assertThat(invOf(productId)).isEqualTo(
                new Inv(baseline.available() - 1, baseline.reserved() + 1));

        // The human approves; run 2 pays, pauses at capture, then
        // resumes to verify (post-consent runs are legal).
        Map<String, Object> approved = post(token,
                "/api/v1/agent/sessions/" + sessionId + "/consent/confirm",
                Map.of());
        assertThat(approved.get("consentState")).isEqualTo("CONFIRMED");

        modelWill(
                jsonDecision("initiate_payment", Map.of("orderId", orderId)),
                jsonDecision("done", Map.of()));
        post(token, "/api/v1/agent/sessions/" + sessionId + "/run", Map.of());
        long paymentId = paymentIdOf(token, sessionId);

        modelWill(
                jsonDecision("confirm_mock_payment",
                        Map.of("paymentId", paymentId)),
                jsonDecision("get_order_status", Map.of("orderId", orderId)),
                jsonDecision("done", Map.of()));
        Map<String, Object> paid = post(token,
                "/api/v1/agent/sessions/" + sessionId + "/run", Map.of());

        assertThat(((Number) paid.get("reservedSpend")).doubleValue())
                .isEqualTo(3000.00);
        var paidCalls = callsOf(paid);
        assertThat(paidCalls.stream().map(c -> c.get("tool"))).containsExactly(
                "search_products", "add_to_cart", "checkout",
                "request_purchase_consent", "initiate_payment",
                "confirm_mock_payment", "get_order_status");

        Map<String, Object> order = get(token, "/api/v1/orders/" + orderId);
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        assertThat(invOf(productId)).isEqualTo(
                new Inv(baseline.available() - 1, baseline.reserved()));

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_events WHERE session_id = ? "
                        + "AND type = 'SPEND_RESERVED'",
                Integer.class, sessionId);
        assertThat(ledgerRows).isEqualTo(1);

        // Every stub call carried our credentials.
        assertThat(AUTH_HEADERS).isNotEmpty();
        assertThat(AUTH_HEADERS).containsOnly("Bearer test-key-123");
    }

    @Test
    void declined_consent_never_spends_and_resume_is_harmless() {
        String token = newUser("agent-deny");
        String title = "Agentic Denied Shoe " + System.nanoTime();
        seed(title, 250_000L, 4);
        Long productId = pid();
        Inv baseline = invOf(productId);

        scriptBrowseToConsentAsk(productId, title, 250_000L);
        Map<String, Object> paused = post(token, "/api/v1/agent/sessions",
                Map.of("goal", title));
        long sessionId = ((Number) paused.get("sessionId")).longValue();
        int callsBeforeResume = callsOf(paused).size();
        assertThat(paused.get("consentState")).isEqualTo("REQUESTED");

        // The human says no.
        Map<String, Object> declined = post(token,
                "/api/v1/agent/sessions/" + sessionId + "/consent/cancel",
                Map.of());
        assertThat(declined.get("consentState")).isEqualTo("CANCELLED");

        // A resume attempt cannot resurrect the purchase: the model gets
        // the full audited history (including the SYSTEM decline note in
        // chat) and finishes without touching money.
        modelWill(jsonDecision("done", Map.of()));
        Map<String, Object> after = post(token,
                "/api/v1/agent/sessions/" + sessionId + "/run", Map.of());

        assertThat(after.get("consentState")).isEqualTo("CANCELLED");
        assertThat(((Number) after.get("reservedSpend")).doubleValue())
                .isEqualTo(0.00);
        // "done" adds no audit row: the resumed run executed NOTHING.
        assertThat(callsOf(after)).hasSize(callsBeforeResume);

        // The declined session still holds its pending order from
        // checkout - unpaid, un-captured, and never paid for. Its stock
        // stays reserved until the expiry sweeper reclaims it.
        Map<String, Object> ordersPage = get(token, "/api/v1/orders");
        assertThat(((Number) ordersPage.get("totalElements")).longValue())
                .isEqualTo(1);
        assertThat(invOf(productId)).isEqualTo(
                new Inv(baseline.available() - 1, baseline.reserved() + 1));

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_events WHERE session_id = ? "
                        + "AND type = 'SPEND_RESERVED'",
                Integer.class, sessionId);
        assertThat(ledgerRows).isEqualTo(0);
    }

    @Test
    void malformed_llm_reply_ends_run_as_audited_planner_error() {
        String token = newUser("agent-garbage");
        seed("Agentic Garbage Shoe " + System.nanoTime(), 100_000L, 5);

        REPLIES.add("this is not json at all");
        Map<String, Object> transcript = post(token, "/api/v1/agent/sessions",
                Map.of("goal", "buy the garbage shoe"));

        // Clean HTTP response, no purchase, and the failure is DATA.
        var calls = callsOf(transcript);
        Map<String, Object> last = calls.get(calls.size() - 1);
        assertThat(last.get("tool")).isEqualTo("plan_next_step");
        assertThat(last.get("status")).isEqualTo("ERROR");
        assertThat(last.get("error")).isEqualTo("PLANNER_ERROR");
        assertThat(transcript.get("consentState")).isEqualTo("NONE");
        assertThat(((Number) transcript.get("reservedSpend")).doubleValue())
                .isEqualTo(0.00);
    }
}
