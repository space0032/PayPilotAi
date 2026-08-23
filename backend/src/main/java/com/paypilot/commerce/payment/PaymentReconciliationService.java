package com.paypilot.commerce.payment;

import com.paypilot.commerce.order.domain.Order;
import com.paypilot.commerce.order.domain.OrderStatus;
import com.paypilot.commerce.order.repo.OrderRepository;
import com.paypilot.commerce.payment.domain.Payment;
import com.paypilot.commerce.payment.domain.PaymentStatus;
import com.paypilot.commerce.payment.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Reconciliation sweep: nobody pays forever. Attempts left in CREATED or
 * PAYMENT_PENDING past their TTL are expired atomically and their stock
 * reservation released, so abandoned checkouts cannot starve the catalog.
 *
 * Concurrency: markExpiredIfStillActive is a conditional UPDATE - if a
 * webhook captured or failed the payment between listing and sweeping,
 * the predicate no longer matches and that attempt is skipped untouched.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    /** States whose money-side is still idle enough to expire safely. */
    private static final List<PaymentStatus> EXPIRABLE = List.of(
            PaymentStatus.CREATED, PaymentStatus.PAYMENT_PENDING);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final StockSettlement stockSettlement;
    private final Clock clock;

    public PaymentReconciliationService(PaymentRepository paymentRepository,
                                        OrderRepository orderRepository,
                                        StockSettlement stockSettlement,
                                        Clock clock) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.stockSettlement = stockSettlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paypilot.payments.expiry-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int expired = expireStalePayments();
            if (expired > 0) {
                log.info("Expired {} stale payment attempts", expired);
            }
        } catch (Exception e) {
            // A broken sweep must never kill the scheduler thread.
            log.error("Payment expiry sweep failed", e);
        }
    }

    @Transactional
    public int expireStalePayments() {
        Instant now = clock.instant();
        List<Payment> stale = paymentRepository.findExpiredActive(EXPIRABLE, now);
        int count = 0;
        for (var payment : stale) {
            if (paymentRepository.markExpiredIfStillActive(payment.getId(), EXPIRABLE, now) == 0) {
                continue; // webhook won the race; its outcome governs stock
            }
            Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
            // Release only while THIS order is still waiting for payment -
            // a confirmed/cancelled order already had its units settled.
            if (order != null && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                stockSettlement.releaseSale(order.getId());
            }
            count++;
        }
        return count;
    }
}
