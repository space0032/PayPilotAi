package com.paypilot.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.paypilot.agent.AgentPlanner.StepOutcome;

/**
 * Deterministic stand-in for the LLM planner (paypilot.agent.planner=mock).
 * Walks the canonical journey - search, add, checkout, consent ladder,
 * pay, capture, verify - reading each step's output from the shared
 * context. The step sequence is identical to what a live model is
 * expected to choose, so orchestration/audit/guardrail behavior is
 * exercised identically in both modes.
 */
@Component
@ConditionalOnProperty(name = "paypilot.agent.planner", havingValue = "mock",
        matchIfMissing = true)
public class MockAgentPlanner implements AgentPlanner {

    @Override
    public PlanSession begin(Long sessionId, Long userId, String title,
                             List<Map<String, Object>> history) {
        List<AgentStep> script = script(sessionId, userId, title);
        return new PlanSession() {
            private int cursor = 0;

            @Override
            public Optional<AgentStep> next(Map<String, Object> ctx,
                                           StepOutcome last) {
                if (cursor >= script.size()) {
                    return Optional.empty();
                }
                return Optional.of(script.get(cursor++));
            }
        };
    }

    private List<AgentStep> script(Long sessionId, Long userId, String goal) {
        return List.of(
                new AgentStep("search_products", Map.of("term", goal),
                        (tools, ctx) -> {
                            var results = tools.search(goal);
                            if (results.isEmpty()) {
                                return Map.of("matches", 0);
                            }
                            var first = results.get(0);
                            return Map.<String, Object>of(
                                    "matches", results.size(),
                                    "productId", first.id(),
                                    "sku", first.sku(),
                                    "title", first.title());
                        }),
                new AgentStep("add_to_cart", Map.of("quantity", 1),
                        (tools, ctx) -> {
                            long productId = chosenProductId(ctx);
                            var cart = tools.addToCart(userId, productId, 1);
                            return Map.of("items", cart.totalItems(),
                                    "subtotal", cart.subtotal().toPlainString());
                        }),
                new AgentStep("checkout", Map.of(),
                        (tools, ctx) -> {
                            var order = tools.checkout(userId, sessionId);
                            ctx.put("orderId", order.orderId());
                            return Map.of(
                                    "orderId", order.orderId(),
                                    "total", order.total().toPlainString(),
                                    "status", order.status());
                        }),
                new AgentStep("request_purchase_consent", Map.of(),
                        (tools, ctx) -> {
                            long amountPaise = paise((String)
                                    ((Map<?, ?>) ctx.get("checkout")).get("total"));
                            tools.requestConsent(userId, sessionId, amountPaise);
                            return Map.of("state", "REQUESTED");
                        }),
                // The mock planner plays the approving human immediately.
                // A live session stops here and waits for the real one.
                new AgentStep("confirm_purchase_consent", Map.of(),
                        (tools, ctx) -> {
                            tools.confirmConsent(userId, sessionId);
                            return Map.of("state", "CONFIRMED");
                        }),
                new AgentStep("initiate_payment", Map.of(),
                        (tools, ctx) -> {
                            long orderId = orderId(ctx);
                            var payment = tools.pay(userId, sessionId, orderId);
                            ctx.put("paymentId", payment.paymentId());
                            return Map.of(
                                    "paymentId", payment.paymentId(),
                                    "gatewayOrderId", payment.gatewayOrderId(),
                                    "amount", payment.amount().toPlainString(),
                                    "status", payment.status());
                        }),
                new AgentStep("confirm_mock_payment", Map.of(),
                        (tools, ctx) -> {
                            long paymentId = ((Number) ctx.get("paymentId")).longValue();
                            tools.confirmMockPayment(userId, paymentId);
                            return Map.of("confirmed", true);
                        }),
                new AgentStep("get_order_status", Map.of(),
                        (tools, ctx) -> {
                            long orderId = orderId(ctx);
                            var order = tools.orderStatus(userId, orderId);
                            return Map.of("status", order.status());
                        }));
    }

    /** Resume support: a paused run picks up where its trace left off. */
    private static long chosenProductId(Map<String, Object> ctx) {
        return number(ctx, "search_products", "productId");
    }

    private static long orderId(Map<String, Object> ctx) {
        return number(ctx, "checkout", "orderId");
    }

    private static long number(Map<String, Object> ctx, String step, String field) {
        Object fromCtx = ctx.get(step);
        if (fromCtx == null && ctx.containsKey(field)) {
            return ((Number) ctx.get(field)).longValue();
        }
        var result = (Map<?, ?>) fromCtx;
        if (!result.containsKey(field)) {
            throw new IllegalStateException(
                    "No %s was recorded by an earlier %s step".formatted(field, step));
        }
        return ((Number) result.get(field)).longValue();
    }

    private long paise(String rupees) {
        return new java.math.BigDecimal(rupees).movePointRight(2).longValueExact();
    }
}
