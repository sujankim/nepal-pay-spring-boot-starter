package io.nepalpay.esewa;

import io.nepalpay.esewa.model.EsewaPaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EsewaPaymentStatus enum.
 * Pure unit test — no Spring context, no network.
 */
@DisplayName("EsewaPaymentStatus")
class EsewaPaymentStatusTest {

    // ── fromString() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromString: COMPLETE → COMPLETE")
    void fromString_complete() {
        assertThat(EsewaPaymentStatus.fromString("COMPLETE"))
                .isEqualTo(EsewaPaymentStatus.COMPLETE);
    }

    @Test
    @DisplayName("fromString: is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(EsewaPaymentStatus.fromString("complete"))
                .isEqualTo(EsewaPaymentStatus.COMPLETE);
        assertThat(EsewaPaymentStatus.fromString("Complete"))
                .isEqualTo(EsewaPaymentStatus.COMPLETE);
    }

    @Test
    @DisplayName("fromString: INCOMPLETE → INCOMPLETE")
    void fromString_incomplete() {
        assertThat(EsewaPaymentStatus.fromString("INCOMPLETE"))
                .isEqualTo(EsewaPaymentStatus.INCOMPLETE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "GARBAGE", "paid", "success", "COMPLETED"})
    @DisplayName("fromString: null, blank, or unknown → UNKNOWN")
    void fromString_unknownValues_returnUnknown(String input) {
        // Note: eSewa uses "COMPLETE" not "COMPLETED" — common mistake!
        assertThat(EsewaPaymentStatus.fromString(input))
                .isEqualTo(EsewaPaymentStatus.UNKNOWN);
    }

    // ── isSuccess() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSuccess: only COMPLETE returns true")
    void isSuccess_onlyCompleteIsTrue() {
        assertThat(EsewaPaymentStatus.COMPLETE.isSuccess()).isTrue();

        assertThat(EsewaPaymentStatus.INCOMPLETE.isSuccess()).isFalse();
        assertThat(EsewaPaymentStatus.UNKNOWN.isSuccess()).isFalse();
    }
}