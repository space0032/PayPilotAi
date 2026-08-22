package com.paypilot.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void ofRupees_convertsToPaise() {
        assertThat(Money.ofRupees(4999).paise()).isEqualTo(499_900L);
    }

    @Test
    void negativeMoney_isRejected() {
        assertThatThrownBy(() -> Money.ofPaise(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addition_sumsPaiseExactly() {
        assertThat(Money.ofPaise(10_50).plus(Money.ofPaise(20_75)).paise())
                .isEqualTo(31_25L);
    }

    @Test
    void multiplication_byQuantity_isExact() {
        assertThat(Money.ofRupees(1_234).times(3).paise()).isEqualTo(370_200L);
    }

    @Test
    void multiplication_rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> Money.ofRupees(100).times(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subtraction_belowZero_isRejected_notSilentlyNegative() {
        assertThatThrownBy(() -> Money.ofPaise(100).minus(Money.ofPaise(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflow_throwsInsteadOfWrapping() {
        long nearMax = Long.MAX_VALUE / 2 + 1;
        assertThatThrownBy(() -> Money.ofPaise(nearMax).plus(Money.ofPaise(nearMax)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparison_worksOnPaise() {
        assertThat(Money.ofRupees(100)).isGreaterThan(Money.ofRupees(99));
        assertThat(Money.ZERO.isZero()).isTrue();
    }

    @Test
    void rupeeDecimal_roundingIsHalfUpAndExactInPaise() {
        Money money = Money.fromRupeeDecimal(new BigDecimal("49.999"));
        // 49.999 rounds HALF_UP to 50.00 -> 5000 paise
        assertThat(money.paise()).isEqualTo(5_000L);
        assertThat(money.toRupees()).isEqualTo(new BigDecimal("50.00"));
    }
}
