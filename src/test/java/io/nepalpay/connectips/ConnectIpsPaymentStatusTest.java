package io.nepalpay.connectips;

import io.nepalpay.connectips.model.ConnectIpsPaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConnectIpsPaymentStatus enum.
 * Pure unit test — no Spring context, no network.
 */
@DisplayName("ConnectIpsPaymentStatus")
class ConnectIpsPaymentStatusTest {

    @Test
    @DisplayName("fromString: SUCCESS parses correctly")
    void fromString_success() {
        assertThat(ConnectIpsPaymentStatus.fromString("SUCCESS"))
                .isEqualTo(ConnectIpsPaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("fromString: is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(ConnectIpsPaymentStatus.fromString("success"))
                .isEqualTo(ConnectIpsPaymentStatus.SUCCESS);
        assertThat(ConnectIpsPaymentStatus.fromString("Success"))
                .isEqualTo(ConnectIpsPaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("fromString: FAILED parses correctly")
    void fromString_failed() {
        assertThat(ConnectIpsPaymentStatus.fromString("FAILED"))
                .isEqualTo(ConnectIpsPaymentStatus.FAILED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "COMPLETE", "Completed", "paid", "RANDOM"})
    @DisplayName("fromString: null, blank, or unknown → UNKNOWN")
    void fromString_unknownValues_returnUnknown(String input) {
        assertThat(ConnectIpsPaymentStatus.fromString(input))
                .isEqualTo(ConnectIpsPaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("isSuccess: only SUCCESS returns true")
    void isSuccess_onlySuccessIsTrue() {
        assertThat(ConnectIpsPaymentStatus.SUCCESS.isSuccess()).isTrue();
        assertThat(ConnectIpsPaymentStatus.FAILED.isSuccess()).isFalse();
        assertThat(ConnectIpsPaymentStatus.UNKNOWN.isSuccess()).isFalse();
    }
}