package com.lokesh_codes.expense_tracker_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a statement description reduces to.
 *
 * <p>No Spring context: this is a pure function, and the whole point of these
 * cases is that they are cheap enough to run while tuning the reduction against
 * a real statement.
 */
class MerchantKeyTest {

    @Test
    @DisplayName("a UPI line reduces to the shop, not the handle or the reference")
    void upiLine() {
        assertThat(MerchantKey.of("UPI-SWIGGY-1234@ybl")).isEqualTo("swiggy");
    }

    @Test
    @DisplayName("a card line reduces past the channel and the masked card number")
    void cardLine() {
        assertThat(MerchantKey.of("POS 4123XXXX9876 AMAZON PAY IND")).isEqualTo("amazon");
    }

    @Test
    @DisplayName("the same shop written four ways is one merchant")
    void oneMerchantAcrossFormats() {
        // The reason the whole feature works. If these drift apart, a hundred
        // rows become four groups and history learnt from one teaches the
        // others nothing.
        assertThat(MerchantKey.of("UPI-SWIGGY-1234@ybl")).isEqualTo("swiggy");
        assertThat(MerchantKey.of("POS 4123XXXX9876 SWIGGY BANGALORE")).isEqualTo("swiggy");
        assertThat(MerchantKey.of("UPI/SWIGGY/998877/Payment from ph")).isEqualTo("swiggy");
        assertThat(MerchantKey.of("Swiggy Instamart Order")).isEqualTo("swiggy");
    }

    @Test
    @DisplayName("a wallet is not a merchant, so what it paid for comes through")
    void aggregatorIsNotTheMerchant() {
        // Without this, every wallet payment on the statement groups under
        // "paytm" and two unrelated shops become one decision.
        assertThat(MerchantKey.of("PAYTM-SWIGGY-123")).isEqualTo("swiggy");
        assertThat(MerchantKey.of("PAYTM-UBER-456")).isEqualTo("uber");
    }

    @Test
    @DisplayName("a salary line is the same merchant every month")
    void monthsAreNotPartOfTheName() {
        assertThat(MerchantKey.of("NEFT-HDFC0001234-SALARY AUG")).isEqualTo("salary");
        assertThat(MerchantKey.of("NEFT-HDFC0001234-SALARY SEP")).isEqualTo("salary");
    }

    @Test
    @DisplayName("a line with no name in it has no merchant")
    void nothingToGroupBy() {
        // Null rather than "upi": a group named after a channel prefix would
        // collect every unrelated row on the statement.
        assertThat(MerchantKey.of("UPI-000000-9911")).isNull();
        assertThat(MerchantKey.of("  ")).isNull();
        assertThat(MerchantKey.of(null)).isNull();
    }
}
