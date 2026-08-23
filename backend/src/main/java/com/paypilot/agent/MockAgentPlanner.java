package com.paypilot.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Deterministic stand-in for the LLM planner (paypilot.agent.planner=mock).
 * Scripts the canonical journey INCLUDING the consent ladder: the agent
 * must formally request spending approval and receive it before payment -
 * exactly the steps a live model will be required to take.
 */
@Component
@ConditionalOnProperty(name = "paypilot.agent.planner", havingValue = "mock",
        matchIfMissing = true)
public class MockAgentPlanner implements AgentPlanner {

    @Override
    public List<AgentStep> plan(Long sessionId, Long userId, String goal) {
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
                new AgentStep("confirm_purchase_consent", Map.of(),
                        (tools, ctx) -> {
                            // Mock mode: the planner plays the approving user.
                            tools.confirmConsent(userId, sessionId);
                            return Map.of("state", "CONFIRMED");
                        }),
                new AgentStep("initiate_payment", Map.of(),
                        (tools, ctx) -> {
                            long orderId = (Long) ctx.get("orderId");
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
                            long paymentId = (Long) ctx.get("paymentId");
                            tools.confirmMockPayment(userId, paymentId);
                            return Map.of("confirmed", true);
                        }),
                new AgentStep("get_order_status", Map.of(),
                        (tools, ctx) -> {
                            long orderId = (Long) ctx.get("orderId");
                            var order = tools.orderStatus(userId, orderId);
                            return Map.of("status", order.status());
                        }));
    }

    private long chosenProductId(Map<String, Object> ctx) {
        var search = (Map<?, ?>) ctx.get("search_products");
        if (search == null || !search.containsKey("productId")) {
            throw new IllegalStateException("No product was selected by search");
        }
        return ((Number) search.get("productId")).longValue();
    }

    private long paise(String rupees) {
        return new java.math.BigDecimal(rupees).movePointRight(2).longValueExact();
    }
}
