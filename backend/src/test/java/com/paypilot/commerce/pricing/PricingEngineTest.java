package com.paypilot.commerce.pricing;

import com.paypilot.commerce.offer.domain.OfferType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingEngineTest {

    private final PricingEngine engine = new PricingEngine();

    @Test
    void percentage_exactDivision() {
        // 10% of Rs 2000.00 = Rs 200.00
        assertThat(engine.quote(OfferType.PERCENTAGE, 1000, null, 200_000L)).isEqualTo(20_000);
    }

    @Test
    void percentage_floorsPartialPaise() {
        // 10% of 19999 paise = 1999.9 -> floor to 1999 (never over-discount)
        assertThat(engine.quote(OfferType.PERCENTAGE, 1000, null, 19_999L)).isEqualTo(1_999);
    }

    @Test
    void percentage_maxCapApplies() {
        // 10% of Rs 6000 = Rs 600 but cap is Rs 500
        assertThat(engine.quote(OfferType.PERCENTAGE, 1000, 50_000L, 600_000L)).isEqualTo(50_000);
    }

    @Test
    void flat_discountIsFixedAmount() {
        assertThat(engine.quote(OfferType.FLAT, 50_000L, null, 450_000L)).isEqualTo(50_000);
    }

    @Test
    void discount_neverExceedsSubtotal() {
        assertThat(engine.quote(OfferType.FLAT, 50_000L, null, 10_000L)).isEqualTo(10_000);
    }

    @Test
    void negativeSubtotal_isRejected() {
        assertThatThrownBy(() -> engine.quote(OfferType.FLAT, 1L, null, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
