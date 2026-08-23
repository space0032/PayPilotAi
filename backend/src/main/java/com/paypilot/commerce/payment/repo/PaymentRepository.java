package com.paypilot.commerce.payment.repo;

import com.paypilot.commerce.payment.domain.Payment;
import com.paypilot.commerce.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);

    boolean existsByRazorpayPaymentIdAndIdNot(String razorpayPaymentId, Long id);

    /**
     * Attempts nobody ever finished paying: still active AND past their
     * expiry. Each row holds a stock reservation hostage until released.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN :activeStatuses AND p.expiresAt < :now
            """)
    List<Payment> findExpiredActive(@Param("activeStatuses") List<PaymentStatus> activeStatuses,
                                    @Param("now") Instant now);

    /**
     * Atomic FSM transition against concurrent webhooks: Postgres re-checks
     * the status predicate after any lock wait, so exactly one actor wins.
     */
    @Modifying
    @Query("""
            UPDATE Payment p
            SET p.status = PaymentStatus.EXPIRED,
                p.failureReason = 'attempt expired before payment',
                p.updatedAt = :now
            WHERE p.id = :id AND p.status IN :activeStatuses
            """)
    int markExpiredIfStillActive(@Param("id") Long id,
                                 @Param("activeStatuses") List<PaymentStatus> activeStatuses,
                                 @Param("now") Instant now);

    /**
     * Cancel-side twin of markExpiredIfStillActive. AUTHORIZED/PROCESSING are
     * deliberately excluded - money is in flight there; only an explicit
     * gateway event may resolve those.
     */
    @Modifying
    @Query("""
            UPDATE Payment p
            SET p.status = PaymentStatus.CANCELLED,
                p.failureReason = 'order cancelled by customer',
                p.updatedAt = :now
            WHERE p.orderId = :orderId
              AND p.status IN (
                  PaymentStatus.CREATED,
                  PaymentStatus.PAYMENT_PENDING)
            """)
    int cancelUnpaidAttempt(@Param("orderId") Long orderId, @Param("now") Instant now);
}
