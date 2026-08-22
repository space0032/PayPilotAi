package com.paypilot.commerce.cart.repo;

import com.paypilot.commerce.cart.domain.Cart;
import com.paypilot.commerce.cart.domain.CartStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * PESSIMISTIC_WRITE serializes all mutations of one user's cart: two
     * concurrent add/update requests queue on the cart row instead of racing
     * on quantities. Different users never contend.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findByUserIdAndStatus(Long userId, CartStatus status);

    default Optional<Cart> findActiveCart(Long userId) {
        return findByUserIdAndStatus(userId, CartStatus.ACTIVE);
    }
}
