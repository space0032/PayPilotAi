package com.paypilot.commerce.offer.domain;

public enum OfferType {
    /** discount_value is basis points: 1000 = 10.00%. */
    PERCENTAGE,
    /** discount_value is a fixed amount in paise. */
    FLAT
}
