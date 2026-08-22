package com.paypilot.commerce.offer;

import com.paypilot.common.error.BadRequestException;
import com.paypilot.commerce.offer.domain.Offer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * The single authority on whether an offer may discount a purchase.
 * Used identically at apply-time (advisory, UX) and checkout-time
 * (authoritative, money) so the two paths can never diverge.
 */
@Component
public class OfferPolicy {

    private final Clock clock;

    public OfferPolicy(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param usedCount how many times this user already redeemed the offer
     * @throws BadRequestException with a stable code on any violation
     */
    public void validate(Offer offer, long subtotalPaise, long usedCount) {
        if (!offer.isActive()) {
            throw new BadRequestException("OFFER_INACTIVE", "This offer is no longer active");
        }
        Instant now = clock.instant();
        if (offer.getValidFrom() != null && now.isBefore(offer.getValidFrom())) {
            throw new BadRequestException("OFFER_NOT_STARTED", "This offer is not active yet");
        }
        if (offer.getValidTo() != null && now.isAfter(offer.getValidTo())) {
            throw new BadRequestException("OFFER_EXPIRED", "This offer has expired");
        }
        if (subtotalPaise < offer.getMinCartPaise()) {
            throw new BadRequestException("MIN_CART_NOT_MET",
                    "Cart must be at least Rs "
                            + BigDecimal.valueOf(offer.getMinCartPaise(), 2).toPlainString()
                            + " for this offer");
        }
        if (usedCount >= offer.getUsageLimitPerUser()) {
            throw new BadRequestException("USAGE_LIMIT_REACHED",
                    "You have already used this offer the maximum number of times");
        }
    }
}
