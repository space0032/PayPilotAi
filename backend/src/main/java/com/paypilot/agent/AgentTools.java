package com.paypilot.agent;

import com.paypilot.agent.domain.AgentMessage;
import com.paypilot.agent.domain.AgentMessageRole;
import com.paypilot.agent.domain.AgentSession;
import com.paypilot.agent.domain.ConsentState;
import com.paypilot.agent.domain.CustomerEvent;
import com.paypilot.agent.repo.AgentMessageRepository;
import com.paypilot.agent.repo.AgentSessionRepository;
import com.paypilot.agent.repo.CustomerEventRepository;
import com.paypilot.commerce.cart.CartService;
import com.paypilot.commerce.cart.api.dto.AddItemRequest;
import com.paypilot.commerce.cart.api.dto.CartResponse;
import com.paypilot.commerce.catalog.CatalogService;
import com.paypilot.commerce.catalog.api.dto.PageResponse;
import com.paypilot.commerce.catalog.api.dto.ProductSummary;
import com.paypilot.commerce.order.OrderService;
import com.paypilot.commerce.order.api.dto.OrderResponse;
import com.paypilot.commerce.payment.PaymentService;
import com.paypilot.commerce.payment.api.dto.PaymentResponse;
import com.paypilot.common.error.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The complete action vocabulary an agent session may use - and the place
 * where AI autonomy meets financial safety:
 *
 *   1. Spend cap: checkout refuses when the live cart total exceeds the
 *      configured per-purchase ceiling (nothing mutated on refusal).
 *   2. Consent gate: payment initiation requires the session's consent
 *      FSM to sit at CONFIRMED; initiating CONSUMES the grant, so one
 *      approval authorizes exactly one purchase.
 * Every method acts as the session's owner; the agent never touches
 * repositories or other users' data directly.
 */
@Service
public class AgentTools {

    private final CatalogService catalogService;
    private final CartService cartService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final CustomerEventRepository events;
    private final long maxSpendPaise;
    private final long dailyCapPaise;
    private final java.time.Clock clock;

    public AgentTools(CatalogService catalogService,
                      CartService cartService,
                      OrderService orderService,
                      PaymentService paymentService,
                      AgentSessionRepository sessions,
                      AgentMessageRepository messages,
                      CustomerEventRepository events,
                      @Value("${paypilot.agent.max-spend-paise:1000000}")
                      long maxSpendPaise,
                      @Value("${paypilot.agent.daily-spend-cap-paise:2000000}")
                      long dailyCapPaise,
                      java.time.Clock clock) {
        this.catalogService = catalogService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.sessions = sessions;
        this.messages = messages;
        this.events = events;
        this.maxSpendPaise = maxSpendPaise;
        this.dailyCapPaise = dailyCapPaise;
        this.clock = clock;
    }

    public List<ProductSummary> search(String term) {
        PageResponse<ProductSummary> page =
                catalogService.listProducts(term, null, null, null, "price_asc", 0, 5, null);
        return page.items();
    }

    public CartResponse addToCart(Long userId, Long productId, int quantity) {
        return cartService.add(userId, new AddItemRequest(productId, quantity));
    }

    public CartResponse viewCart(Long userId) {
        return cartService.view(userId);
    }

    @Transactional
    public OrderResponse checkout(Long userId, Long sessionId) {
        requireOwnedSession(sessionId, userId);
        long cartTotalPaise = viewCart(userId).total().movePointRight(2).longValueExact();
        if (cartTotalPaise > maxSpendPaise) {
            throw new ConflictException("SPEND_CAP_EXCEEDED",
                    ("Cart total %d paise exceeds the per-purchase cap of %d paise")
                            .formatted(cartTotalPaise, maxSpendPaise));
        }
        return orderService.checkout(userId);
    }

    /** The agent formally asks the human for spending power. */
    @Transactional
    public void requestConsent(Long userId, Long sessionId, long amountPaise) {
        AgentSession session = requireOwnedSession(sessionId, userId);
        session.requestConsent();
        sessions.save(session);
        messages.save(new AgentMessage(sessionId, AgentMessageRole.AGENT,
                "I need your approval to spend up to %s for this purchase."
                        .formatted(rupees(amountPaise))));
    }

    /**
     * Human approval. In mock mode the planner plays the human; the live
     * flow will expose this as an explicit user action on the session.
     */
    @Transactional
    public void confirmConsent(Long userId, Long sessionId) {
        AgentSession session = requireOwnedSession(sessionId, userId);
        session.confirmConsent();
        sessions.save(session);
        messages.save(new AgentMessage(sessionId, AgentMessageRole.SYSTEM,
                "User approved the purchase."));
    }

    @Transactional
    public PaymentResponse pay(Long userId, Long sessionId, Long orderId) {
        AgentSession session = requireOwnedSession(sessionId, userId);
        if (!session.consentGranted()) {
            // A refusal, not an error: the guardrail did its job.
            throw new ConflictException("PURCHASE_CONSENT_REQUIRED",
                    "Payment requires explicit user consent before money moves");
        }
        OrderResponse order = orderService.get(userId, orderId);
        long totalPaise = order.total().movePointRight(2).longValueExact();
        if (totalPaise > maxSpendPaise) {
            throw new ConflictException("SPEND_CAP_EXCEEDED",
                    ("Order total %d paise exceeds the per-purchase cap of %d paise")
                            .formatted(totalPaise, maxSpendPaise));
        }
        // Rolling-24h ceiling across every session this user owns: many
        // small approvals must not add up to one large loss.
        long spentTodayPaise = events.sumUserSpendSince(
                userId, java.time.Instant.now(clock).minusSeconds(86_400));
        if (spentTodayPaise + totalPaise > dailyCapPaise) {
            throw new ConflictException("DAILY_SPEND_CAP_EXCEEDED",
                    ("Purchase would take the rolling-24h spend to %d paise, "
                            + "past the %d paise daily cap")
                            .formatted(spentTodayPaise + totalPaise, dailyCapPaise));
        }
        PaymentResponse payment = paymentService.initiate(userId, orderId);
        session.consumeConsent();
        sessions.save(session);
        // The event log is the cumulative-spend ledger: payments carry no
        // session linkage, customer_events do.
        events.save(new CustomerEvent(userId, sessionId,
                CustomerEvent.SPEND_RESERVED,
                Map.of("orderId", order.orderId(),
                        "paymentId", payment.paymentId(),
                        "amountPaise", totalPaise)));
        return payment;
    }

    /** Mock-gateway shortcut through the real signed-webhook pipeline. */
    public void confirmMockPayment(Long userId, Long paymentId) {
        paymentService.simulateCapture(userId, paymentId);
    }

    public OrderResponse orderStatus(Long userId, Long orderId) {
        return orderService.get(userId, orderId);
    }

    // ------------------------------------------------------------------

    private AgentSession requireOwnedSession(Long sessionId, Long userId) {
        AgentSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new ConflictException("SESSION_NOT_FOUND",
                        "Agent session %d does not exist".formatted(sessionId)));
        if (session.getConsentState() == ConsentState.CONSUMED) {
            throw new ConflictException("SESSION_NOT_ACTIVE",
                    "Agent session %d already completed its purchase"
                            .formatted(sessionId));
        }
        return session;
    }

    private static String rupees(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }
}
