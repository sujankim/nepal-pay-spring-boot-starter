package io.nepalpay.core.khalti;


import io.nepalpay.core.khalti.model.KhaltiPaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for KhaltiPaymentStatus enum.
 * Pure unit test — no Spring context, no network, instant execution.
 */
@DisplayName("KhaltiPaymentStatus")
class KhaltiPaymentStatusTest {

    // ── fromString() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromString: COMPLETED → COMPLETED")
    void fromString_completed() {
        assertThat(KhaltiPaymentStatus.fromString("Completed"))
                .isEqualTo(KhaltiPaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("fromString: is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(KhaltiPaymentStatus.fromString("completed"))
                .isEqualTo(KhaltiPaymentStatus.COMPLETED);
        assertThat(KhaltiPaymentStatus.fromString("COMPLETED"))
                .isEqualTo(KhaltiPaymentStatus.COMPLETED);
        assertThat(KhaltiPaymentStatus.fromString("CoMpLeTed"))
                .isEqualTo(KhaltiPaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("fromString: User canceled (with space) → USER_CANCELED")
    void fromString_userCanceled_withSpace() {
        assertThat(KhaltiPaymentStatus.fromString("User canceled"))
                .isEqualTo(KhaltiPaymentStatus.USER_CANCELED);
    }

    @Test
    @DisplayName("fromString: all known statuses parse correctly")
    void fromString_allKnownStatuses() {
        assertThat(KhaltiPaymentStatus.fromString("Canceled"))
                .isEqualTo(KhaltiPaymentStatus.CANCELED);
        assertThat(KhaltiPaymentStatus.fromString("Expired"))
                .isEqualTo(KhaltiPaymentStatus.EXPIRED);
        assertThat(KhaltiPaymentStatus.fromString("Failed"))
                .isEqualTo(KhaltiPaymentStatus.FAILED);
        assertThat(KhaltiPaymentStatus.fromString("Pending"))
                .isEqualTo(KhaltiPaymentStatus.PENDING);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "GARBAGE", "random_string", "paid"})
    @DisplayName("fromString: null, blank, or unknown → UNKNOWN")
    void fromString_unknownValues_returnUnknown(String input) {
        assertThat(KhaltiPaymentStatus.fromString(input))
                .isEqualTo(KhaltiPaymentStatus.UNKNOWN);
    }

    // ── isSuccess() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSuccess: only COMPLETED returns true")
    void isSuccess_onlyCompletedIsTrue() {
        assertThat(KhaltiPaymentStatus.COMPLETED.isSuccess()).isTrue();

        assertThat(KhaltiPaymentStatus.CANCELED.isSuccess()).isFalse();
        assertThat(KhaltiPaymentStatus.USER_CANCELED.isSuccess()).isFalse();
        assertThat(KhaltiPaymentStatus.EXPIRED.isSuccess()).isFalse();
        assertThat(KhaltiPaymentStatus.FAILED.isSuccess()).isFalse();
        assertThat(KhaltiPaymentStatus.PENDING.isSuccess()).isFalse();
        assertThat(KhaltiPaymentStatus.UNKNOWN.isSuccess()).isFalse();
    }

    // ── isTerminalFailure() ───────────────────────────────────────────────────

    @Test
    @DisplayName("isTerminalFailure: CANCELED, USER_CANCELED, EXPIRED, FAILED are terminal")
    void isTerminalFailure_correctStatuses() {
        assertThat(KhaltiPaymentStatus.CANCELED.isTerminalFailure()).isTrue();
        assertThat(KhaltiPaymentStatus.USER_CANCELED.isTerminalFailure()).isTrue();
        assertThat(KhaltiPaymentStatus.EXPIRED.isTerminalFailure()).isTrue();
        assertThat(KhaltiPaymentStatus.FAILED.isTerminalFailure()).isTrue();

        // PENDING and UNKNOWN are NOT terminal — may still resolve
        assertThat(KhaltiPaymentStatus.PENDING.isTerminalFailure()).isFalse();
        assertThat(KhaltiPaymentStatus.UNKNOWN.isTerminalFailure()).isFalse();
        assertThat(KhaltiPaymentStatus.COMPLETED.isTerminalFailure()).isFalse();
    }

    // ── REFUNDED ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromString: Refunded → REFUNDED")
    void fromString_refunded() {
        assertThat(KhaltiPaymentStatus.fromString("Refunded"))
                .isEqualTo(KhaltiPaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("fromString: REFUNDED is case-insensitive")
    void fromString_refunded_caseInsensitive() {
        assertThat(KhaltiPaymentStatus.fromString("refunded"))
                .isEqualTo(KhaltiPaymentStatus.REFUNDED);
        assertThat(KhaltiPaymentStatus.fromString("REFUNDED"))
                .isEqualTo(KhaltiPaymentStatus.REFUNDED);
    }

    // ── isRefunded() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("isRefunded: only REFUNDED returns true")
    void isRefunded_onlyRefundedIsTrue() {
        assertThat(KhaltiPaymentStatus.REFUNDED.isRefunded()).isTrue();

        assertThat(KhaltiPaymentStatus.COMPLETED.isRefunded()).isFalse();
        assertThat(KhaltiPaymentStatus.PENDING.isRefunded()).isFalse();
        assertThat(KhaltiPaymentStatus.CANCELED.isRefunded()).isFalse();
        assertThat(KhaltiPaymentStatus.FAILED.isRefunded()).isFalse();
        assertThat(KhaltiPaymentStatus.UNKNOWN.isRefunded()).isFalse();
    }

    // ── isSuccess() update ────────────────────────────────────────────────────

    @Test
    @DisplayName("isSuccess: REFUNDED returns false (was paid but reversed)")
    void isSuccess_refunded_returnsFalse() {
        // A refunded payment was once successful but is now reversed.
        // isSuccess() must return false — you cannot fulfil a refunded order.
        assertThat(KhaltiPaymentStatus.REFUNDED.isSuccess()).isFalse();
    }

    // ── isTerminalFailure() update ────────────────────────────────────────────

    @Test
    @DisplayName("isTerminalFailure: REFUNDED is NOT a terminal failure")
    void isTerminalFailure_refunded_isFalse() {
        // REFUNDED is a special state — not a failure, not a success.
        // It is handled via isRefunded() separately.
        assertThat(KhaltiPaymentStatus.REFUNDED.isTerminalFailure()).isFalse();
    }
}