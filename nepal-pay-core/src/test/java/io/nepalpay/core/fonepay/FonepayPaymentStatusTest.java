package io.nepalpay.core.fonepay;

import io.nepalpay.core.fonepay.model.FonepayPaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FonepayPaymentStatus enum.
 * Pure unit test — no Spring context, no network.
 */
@DisplayName("FonepayPaymentStatus")
class FonepayPaymentStatusTest {

    @Test
    @DisplayName("fromString: success → SUCCESS")
    void fromString_success() {
        assertThat(FonepayPaymentStatus.fromString("success"))
                .isEqualTo(FonepayPaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("fromString: is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(FonepayPaymentStatus.fromString("SUCCESS"))
                .isEqualTo(FonepayPaymentStatus.SUCCESS);
        assertThat(FonepayPaymentStatus.fromString("Success"))
                .isEqualTo(FonepayPaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("fromString: true → SUCCESS")
    void fromString_true() {
        assertThat(FonepayPaymentStatus.fromString("true"))
                .isEqualTo(FonepayPaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("fromString: failed → FAILED")
    void fromString_failed() {
        assertThat(FonepayPaymentStatus.fromString("failed"))
                .isEqualTo(FonepayPaymentStatus.FAILED);
        assertThat(FonepayPaymentStatus.fromString("false"))
                .isEqualTo(FonepayPaymentStatus.FAILED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "COMPLETE", "Completed", "paid", "RANDOM"})
    @DisplayName("fromString: null, blank, or unknown → UNKNOWN")
    void fromString_unknownValues(String input) {
        assertThat(FonepayPaymentStatus.fromString(input))
                .isEqualTo(FonepayPaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("isSuccess: only SUCCESS returns true")
    void isSuccess_onlySuccessIsTrue() {
        assertThat(FonepayPaymentStatus.SUCCESS.isSuccess()).isTrue();
        assertThat(FonepayPaymentStatus.FAILED.isSuccess()).isFalse();
        assertThat(FonepayPaymentStatus.UNKNOWN.isSuccess()).isFalse();
    }
}