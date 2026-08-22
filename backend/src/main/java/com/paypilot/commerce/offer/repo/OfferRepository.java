package com.paypilot.commerce.offer.repo;

import com.paypilot.commerce.offer.domain.Offer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    Optional<Offer> findByCodeIgnoreCase(String code);
}
