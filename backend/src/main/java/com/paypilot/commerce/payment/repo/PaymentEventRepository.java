package com.paypilot.commerce.payment.repo;

import com.paypilot.commerce.payment.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    boolean existsByEventId(String eventId);
}
