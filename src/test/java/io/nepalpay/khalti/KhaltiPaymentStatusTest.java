package io.nepalpay.khalti;

import io.nepalpay.khalti.model.KhaltiPaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

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
}