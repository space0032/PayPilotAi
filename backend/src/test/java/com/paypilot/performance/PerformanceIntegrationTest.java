package com.paypilot.performance;

import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 18: concurrent load on the hot paths. Virtual threads let every
 * request block on its own carrier without starving the others, so we
 * see true lock contention and connection-pool pressure rather than
 * thread-pool exhaustion. Each path asserts a p95 latency ceiling;
 * breaching it means a regression worth investigating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paypilot.security.rate-limit.auth-capacity-per-minute=500",
        "paypilot.payments.expiry-sweep-interval-ms=3600000"})
@Testcontainers(disabledWithoutDocker = true)
class PerformanceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SKU = "SHOE-NK-DOWN12";

    @Autowired TestRestTemplate http;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(s -> {
            Long pid = productRepository.findBySku(SKU).orElseThrow().getId();
            productRepository.setPricePaise(pid, 50_000L);
            inventoryRepository.setAvailable(pid, 500);
        });
    }

    private String register(String label) {
        return (String) http.postForEntity("/api/v1/auth/register",
                Map.of("email", label + "-" + System.nanoTime() + "@test.com",
                        "password", "perf1234!"),
                Map.class).getBody().get("accessToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private record LatencyResult(long p50, long p95, long max, int count) {}

    private LatencyResult measure(List<Long> sorted) {
        int n = sorted.size();
        return new LatencyResult(
                sorted.get(n / 2),
                sorted.get((int) (n * 0.95)),
                sorted.get(n - 1),
                n);
    }

    // ---------------------------------------------------------------
    // 1. Search — read-heavy, trigram GiST index
    // ---------------------------------------------------------------
    @Test
    void search_hotPath_p95Under2s() throws Exception {
        String token = register("perf-search");
        int concurrency = 20;

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                long start = System.nanoTime();
                ResponseEntity<String> resp = http.exchange(
                        "/api/v1/products?q=shoe&size=5", HttpMethod.GET,
                        new HttpEntity<>(null, bearer(token)), String.class);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(ms);
                assertThat(resp.getStatusCode().value()).isEqualTo(200);
            });
        }
        ready.await();
        go.countDown();
        exec.shutdown();
        exec.awaitTermination(60, TimeUnit.SECONDS);

        List<Long> sorted = latencies.stream().sorted().toList();
        LatencyResult r = measure(sorted);
        assertThat(r.p95()).as("search p95").isLessThan(2000);
        System.out.printf("[PERF] search: %d requests, p50=%dms p95=%dms max=%dms%n",
                r.count(), r.p50(), r.p95(), r.max());
    }

    // ---------------------------------------------------------------
    // 2. Cart add — pessimistic lock contention
    // ---------------------------------------------------------------
    @Test
    void cartAdd_hotPath_p95Under3s() throws Exception {
        String token = register("perf-cart");
        int concurrency = 10;
        Long pid = productRepository.findBySku(SKU).orElseThrow().getId();

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            final int qty = 1;
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                long start = System.nanoTime();
                ResponseEntity<Map> resp = http.exchange(
                        "/api/v1/cart/items", HttpMethod.POST,
                        new HttpEntity<>(Map.of("productId", pid, "quantity", qty),
                                bearer(token)), Map.class);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(ms);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    successCount.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();
        exec.shutdown();
        exec.awaitTermination(60, TimeUnit.SECONDS);

        List<Long> sorted = latencies.stream().sorted().toList();
        LatencyResult r = measure(sorted);
        assertThat(r.p95()).as("cart add p95").isLessThan(3000);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
        System.out.printf("[PERF] cart add: %d requests, p50=%dms p95=%dms max=%dms%n",
                r.count(), r.p50(), r.p95(), r.max());
    }

    // ---------------------------------------------------------------
    // 3. Checkout — atomic reservation + offer validation
    // ---------------------------------------------------------------
    @Test
    void checkout_eachCompletesUnder5s() throws Exception {
        int concurrency = 5;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final String label = "perf-checkout-" + i;
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                String tok = register(label);
                http.exchange("/api/v1/cart/items", HttpMethod.POST,
                        new HttpEntity<>(Map.of("productId",
                                productRepository.findBySku(SKU).orElseThrow().getId(),
                                "quantity", 1), bearer(tok)), Void.class);
                long start = System.nanoTime();
                ResponseEntity<Map> resp = http.exchange(
                        "/api/v1/orders", HttpMethod.POST,
                        new HttpEntity<>(null, bearer(tok)), Map.class);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(ms);
                assertThat(resp.getStatusCode().value()).isEqualTo(200);
            });
        }
        ready.await();
        go.countDown();
        exec.shutdown();
        exec.awaitTermination(120, TimeUnit.SECONDS);

        List<Long> sorted = latencies.stream().sorted().toList();
        LatencyResult r = measure(sorted);
        assertThat(r.max()).as("checkout max").isLessThan(5000);
        System.out.printf("[PERF] checkout: %d requests, p50=%dms p95=%dms max=%dms%n",
                r.count(), r.p50(), r.p95(), r.max());
    }

    // ---------------------------------------------------------------
    // 4. Agent start — mock planner tool-call loop
    // ---------------------------------------------------------------
    @Test
    void agentStart_eachCompletesUnder15s() throws Exception {
        int concurrency = 5;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final String label = "perf-agent-" + i;
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                String tok = register(label);
                long start = System.nanoTime();
                ResponseEntity<Map> resp = http.exchange(
                        "/api/v1/agent/sessions", HttpMethod.POST,
                        new HttpEntity<>(Map.of("goal", "buy shoes"),
                                bearer(tok)), Map.class);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(ms);
                assertThat(resp.getStatusCode().value()).isBetween(200, 201);
            });
        }
        ready.await();
        go.countDown();
        exec.shutdown();
        exec.awaitTermination(120, TimeUnit.SECONDS);

        List<Long> sorted = latencies.stream().sorted().toList();
        LatencyResult r = measure(sorted);
        assertThat(r.max()).as("agent start max").isLessThan(15000);
        System.out.printf("[PERF] agent start: %d requests, p50=%dms p95=%dms max=%dms%n",
                r.count(), r.p50(), r.p95(), r.max());
    }
}
