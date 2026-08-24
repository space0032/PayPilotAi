package com.paypilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The real brain. Each {@code next()} appends the latest tool outcome to
 * the conversation, asks the model for exactly one JSON action, and wraps
 * it as an executable step. The model can only ever ASK - every spend-
 * touching request still passes through AgentTools' cap and consent
 * guardrails, and the human-only actions (confirming consent) are not
 * even in its vocabulary: approval arrives through the session API.
 *
 * Wire protocol (strict, temperature 0):
 *   {"tool": "<name>", "arguments": {...}}   execute one tool call
 *   {"tool": "done"}                          end the run cleanly
 */
@Component
@ConditionalOnProperty(name = "paypilot.agent.planner", havingValue = "live")
public class LiveAgentPlanner implements AgentPlanner {

    private static final Set<String> TOOLS = Set.of(
            "search_products", "add_to_cart", "view_cart", "checkout",
            "request_purchase_consent", "initiate_payment",
            "confirm_mock_payment", "get_order_status", "done");

    private final LlmPort llm;
    private final ObjectMapper mapper;
    private final String model;

    public LiveAgentPlanner(LlmPort llm,
                            ObjectMapper mapper,
                            @Value("${paypilot.agent.llm.model}") String model) {
        this.llm = llm;
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    public PlanSession begin(Long sessionId, Long userId, String title,
                             List<Map<String, Object>> history) {
        List<LlmPort.LlmMessage> convo = new ArrayList<>();
        convo.add(LlmPort.LlmMessage.system(systemPrompt()));
        convo.add(LlmPort.LlmMessage.user("Shopping goal: " + title));
        if (!history.isEmpty()) {
            convo.add(LlmPort.LlmMessage.user(
                    "Already executed in earlier runs of this session "
                            + "(JSON audit): " + json(history)));
        }
        return new PlanSession() {
            @Override
            public Optional<AgentStep> next(Map<String, Object> ctx,
                                           StepOutcome last) {
                if (last != null) {
                    Map<String, Object> observation = last.success()
                            ? Map.of("tool", last.tool(), "ok", true,
                                    "result", last.result() == null
                                            ? Map.of() : last.result())
                            : Map.of("tool", last.tool(), "ok", false,
                                    "error", last.errorCode());
                    convo.add(LlmPort.LlmMessage.user(json(observation)));
                }
                JsonNode action = parse(llm.complete(convo));
                String tool = action.path("tool").asText("");
                if (!TOOLS.contains(tool)) {
                    throw new IllegalStateException(
                            "Planner proposed unknown tool '" + tool + "'");
                }
                if (tool.equals("done")) {
                    return Optional.empty();
                }
                Map<String, Object> args = mapper.convertValue(
                        action.path("arguments"),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                java.util.LinkedHashMap<String, Object>>() {
                        });
                return Optional.of(step(sessionId, userId, tool, args));
            }
        };
    }

    // ------------------------------------------------------------------

    private AgentStep step(Long sessionId, Long userId, String tool,
                           Map<String, Object> args) {
        return new AgentStep(tool, args, (tools, ctx) -> switch (tool) {
            case "search_products" -> {
                var results = tools.search(text(args, "term"));
                if (results.isEmpty()) {
                    yield Map.<String, Object>of("matches", 0);
                }
                var first = results.get(0);
                yield Map.<String, Object>of(
                        "matches", results.size(),
                        "productId", first.id(),
                        "sku", first.sku(),
                        "title", first.title());
            }
            case "add_to_cart" -> {
                var cart = tools.addToCart(userId, num(args, "productId"),
                        args.containsKey("quantity")
                                ? (int) num(args, "quantity") : 1);
                yield Map.<String, Object>of(
                        "items", cart.totalItems(),
                        "subtotal", cart.subtotal().toPlainString());
            }
            case "view_cart" -> tools.viewCart(userId);
            case "checkout" -> {
                var order = tools.checkout(userId, sessionId);
                ctx.put("orderId", order.orderId());
                yield Map.<String, Object>of(
                        "orderId", order.orderId(),
                        "total", order.total().toPlainString(),
                        "status", order.status());
            }
            case "request_purchase_consent" -> {
                long amountPaise = args.containsKey("amountPaise")
                        ? num(args, "amountPaise")
                        : totalPaiseOf(ctx);
                tools.requestConsent(userId, sessionId, amountPaise);
                yield Map.<String, Object>of("state", "REQUESTED");
            }
            case "initiate_payment" -> {
                var payment = tools.pay(userId, sessionId,
                        num(args, "orderId"));
                ctx.put("paymentId", payment.paymentId());
                yield Map.<String, Object>of(
                        "paymentId", payment.paymentId(),
                        "gatewayOrderId", payment.gatewayOrderId(),
                        "amount", payment.amount().toPlainString(),
                        "status", payment.status());
            }
            case "confirm_mock_payment" -> {
                tools.confirmMockPayment(userId, num(args, "paymentId"));
                yield Map.<String, Object>of("confirmed", true);
            }
            case "get_order_status" ->
                    tools.orderStatus(userId, num(args, "orderId"));
            default -> throw new IllegalStateException(
                    "Tool '" + tool + "' has no executor");
        });
    }

    private String systemPrompt() {
        return """
                You are PayPilot's shopping agent. You operate a user's cart \
                and checkout on their behalf. You MUST reply with EXACTLY one \
                JSON object and nothing else:
                  {"tool": "<name>", "arguments": {...}}  to run one tool, or
                  {"tool": "done"}  when the journey is complete or paused.

                Available tools:
                 - search_products      {"term": string}          find products
                 - add_to_cart          {"productId": number, "quantity": number}
                 - view_cart            {}
                 - checkout             {}                        creates the order from the cart
                 - request_purchase_consent {"amountPaise": number}  ask the human for spending approval
                 - initiate_payment     {"orderId": number}       REQUIRES prior human consent; consumes it
                 - confirm_mock_payment {"paymentId": number}     mock-gateway capture only
                 - get_order_status     {"orderId": number}
                 - done                 -

                Hard rules:
                 1. Never fabricate ids; use values returned by previous tool results.
                 2. After checkout, ALWAYS call request_purchase_consent with the \
                order total in paise, then reply {"tool":"done"} - you must WAIT \
                for the human's approval via the app.
                 3. You cannot approve your own purchase; there is no such tool.
                 4. If a tool result reports ok:false, do not retry blindly - \
                adjust or finish with done.
                Model: %s.""".formatted(model);
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (!node.isObject()) {
                throw new IllegalStateException(
                        "Planner replied with non-object JSON: " + raw);
            }
            return node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Planner reply was not JSON: "
                    + raw, e);
        }
    }

    private long totalPaiseOf(Map<String, Object> ctx) {
        Object checkout = ctx.get("checkout");
        if (!(checkout instanceof Map<?, ?> m)
                || !(m.get("total") instanceof String total)) {
            throw new IllegalStateException(
                    "request_purchase_consent without amountPaise and no "
                            + "known checkout total");
        }
        return new java.math.BigDecimal(total).movePointRight(2).longValueExact();
    }

    private static long num(Map<String, Object> args, String field) {
        Object v = args.get(field);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Argument '" + field
                + "' must be a number, got: " + v);
    }

    private static String text(Map<String, Object> args, String field) {
        Object v = args.get(field);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalStateException("Argument '" + field
                + "' must be a non-blank string, got: " + v);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transcript", e);
        }
    }
}
