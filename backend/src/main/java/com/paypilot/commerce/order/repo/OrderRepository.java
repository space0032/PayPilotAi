package com.paypilot.commerce.order.repo;

import com.paypilot.commerce.order.domain.Order;
import com.paypilot.commerce.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Ownership check lives here: a caller must only ever see their own orders. */
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Atomic PENDING_PAYMENT -> CANCELLED against concurrent webhooks: if a
     * capture committed first, the predicate no longer matches and the
     * cancellation aborts instead of cancelling a paid order.
     */
    @Modifying
    @Query("""
            UPDATE Order o
            SET o.status = OrderStatus.CANCELLED,
                o.updatedAt = :now
            WHERE o.id = :id
              AND o.status = OrderStatus.PENDING_PAYMENT
            """)
    int cancelIfPending(@Param("id") Long id, @Param("now") Instant now);
}
