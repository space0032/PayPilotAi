package com.paypilot.commerce.offer.repo;

import com.paypilot.commerce.offer.domain.OfferRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferRedemptionRepository extends JpaRepository<OfferRedemption, Long> {

    long countByOfferIdAndUserId(Long offerId, Long userId);
}
