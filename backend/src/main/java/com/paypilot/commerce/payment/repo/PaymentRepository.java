package com.paypilot.commerce.payment.repo;

import com.paypilot.commerce.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);

    boolean existsByRazorpayPaymentIdAndIdNot(String razorpayPaymentId, Long id);
}
