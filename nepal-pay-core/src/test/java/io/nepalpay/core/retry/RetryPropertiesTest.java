package io.nepalpay.core.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RetryProperties record.
 *
 * <p>Pure unit test — no Spring context, no network, instant execution.
 */
@DisplayName("RetryProperties")
class RetryPropertiesTest {

    // ── DEFAULT constant ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("DEFAULT constant")
    class DefaultConstant {

        @Test
        @DisplayName("DEFAULT is disabled")
        void default_isDisabled() {
            assertThat(RetryProperties.DEFAULT.enabled()).isFalse();
        }

        @Test
        @DisplayName("DEFAULT has expected values")
        void default_hasExpectedValues() {
            RetryProperties d = RetryProperties.DEFAULT;
            assertThat(d.maxAttempts()).isEqualTo(3);
            assertThat(d.initialDelayMs()).isEqualTo(500L);
            assertThat(d.multiplier()).isEqualTo(2.0);
            assertThat(d.maxDelayMs()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("DEFAULT isActive() = false because enabled=false")
        void default_isNotActive() {
            assertThat(RetryProperties.DEFAULT.isActive()).isFalse();
        }

        @Test
        @DisplayName("DISABLED has maxAttempts=0")
        void disabled_hasZeroAttempts() {
            assertThat(RetryProperties.DISABLED.maxAttempts()).isEqualTo(0);
            assertThat(RetryProperties.DISABLED.isActive()).isFalse();
        }
    }

    // ── isActive() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isActive()")
    class IsActive {

        @Test
        @DisplayName("enabled=true + maxAttempts=3 → isActive()=true")
        void isActive_enabledAndPositiveAttempts_returnsTrue() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 2.0, 5000L);
            assertThat(props.isActive()).isTrue();
        }

        @Test
        @DisplayName("enabled=false → isActive()=false regardless of maxAttempts")
        void isActive_disabledFlag_returnsFalse() {
            RetryProperties props = new RetryProperties(
                    false, 3, 500L, 2.0, 5000L);
            assertThat(props.isActive()).isFalse();
        }

        @Test
        @DisplayName("enabled=true + maxAttempts=0 → isActive()=false")
        void isActive_enabledButZeroAttempts_returnsFalse() {
            // enabled=true but maxAttempts=0 means "don't actually retry"
            // isActive() guards against this misconfiguration
            RetryProperties props = new RetryProperties(
                    true, 0, 500L, 2.0, 5000L);
            assertThat(props.isActive()).isFalse();
        }
    }

    // ── nextDelay() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("nextDelay()")
    class NextDelay {

        @Test
        @DisplayName("nextDelay doubles delay with multiplier=2.0")
        void nextDelay_doublesDelay() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 2.0, 5000L);

            assertThat(props.nextDelay(500L)).isEqualTo(1000L);
            assertThat(props.nextDelay(1000L)).isEqualTo(2000L);
            assertThat(props.nextDelay(2000L)).isEqualTo(4000L);
        }

        @Test
        @DisplayName("nextDelay is capped at maxDelayMs")
        void nextDelay_cappedAtMaxDelay() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 2.0, 5000L);

            // 3000 × 2.0 = 6000, but capped at 5000
            assertThat(props.nextDelay(3000L)).isEqualTo(5000L);

            // 5000 × 2.0 = 10000, but capped at 5000
            assertThat(props.nextDelay(5000L)).isEqualTo(5000L);
        }

        @Test
        @DisplayName("nextDelay with multiplier=1.0 keeps constant delay")
        void nextDelay_constantDelayWithMultiplierOne() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 1.0, 5000L);

            assertThat(props.nextDelay(500L)).isEqualTo(500L);
            assertThat(props.nextDelay(500L)).isEqualTo(500L);
        }
    }

    // ── jitter() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("jitter()")
    class Jitter {

        @RepeatedTest(20)
        @DisplayName("jitter stays within ±10% of input value")
        void jitter_staysWithinTenPercent() {
            long input = 1000L;
            long result = RetryProperties.jitter(input);

            // ±10% of 1000 = 900 to 1100
            assertThat(result).isBetween(900L, 1100L);
        }

        @Test
        @DisplayName("jitter(0) returns 0")
        void jitter_zeroInput_returnsZero() {
            assertThat(RetryProperties.jitter(0)).isEqualTo(0);
        }

        @Test
        @DisplayName("jitter never returns negative value")
        void jitter_neverNegative() {
            // Even with a very small input, jitter must not go negative
            for (int i = 0; i < 100; i++) {
                assertThat(RetryProperties.jitter(1L)).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    // ── Compact constructor validation ────────────────────────────────────────

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("throws when maxAttempts is negative")
        void constructor_negativeMaxAttempts_throws() {
            assertThatThrownBy(() ->
                    new RetryProperties(true, -1, 500L, 2.0, 5000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts must be >= 0");
        }

        @Test
        @DisplayName("throws when initialDelayMs is negative")
        void constructor_negativeInitialDelay_throws() {
            assertThatThrownBy(() ->
                    new RetryProperties(true, 3, -1L, 2.0, 5000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("initialDelayMs must be >= 0");
        }

        @Test
        @DisplayName("throws when multiplier is less than 1.0")
        void constructor_multiplierLessThanOne_throws() {
            assertThatThrownBy(() ->
                    new RetryProperties(true, 3, 500L, 0.5, 5000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("multiplier must be >= 1.0");
        }

        @Test
        @DisplayName("throws when maxDelayMs is negative")
        void constructor_negativeMaxDelay_throws() {
            assertThatThrownBy(() ->
                    new RetryProperties(true, 3, 500L, 2.0, -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDelayMs must be >= 0");
        }

        @Test
        @DisplayName("maxAttempts=0 is valid — means retry disabled")
        void constructor_zeroMaxAttempts_isValid() {
            RetryProperties props = new RetryProperties(
                    true, 0, 500L, 2.0, 5000L);
            assertThat(props.maxAttempts()).isEqualTo(0);
            assertThat(props.isActive()).isFalse();
        }

        @Test
        @DisplayName("multiplier=1.0 is valid — constant delay")
        void constructor_multiplierOne_isValid() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 1.0, 5000L);
            assertThat(props.multiplier()).isEqualTo(1.0);
        }
    }

    // ── summary() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("summary()")
    class Summary {

        @Test
        @DisplayName("summary() returns DISABLED when not active")
        void summary_disabled_returnsDisabled() {
            assertThat(RetryProperties.DEFAULT.summary()).isEqualTo("DISABLED");
            assertThat(RetryProperties.DISABLED.summary()).isEqualTo("DISABLED");
        }

        @Test
        @DisplayName("summary() returns config details when active")
        void summary_active_returnsDetails() {
            RetryProperties props = new RetryProperties(
                    true, 3, 500L, 2.0, 5000L);

            String summary = props.summary();
            assertThat(summary).contains("enabled");
            assertThat(summary).contains("maxAttempts=3");
            assertThat(summary).contains("initialDelay=500ms");
            assertThat(summary).contains("multiplier=2.0");
            assertThat(summary).contains("maxDelay=5000ms");
        }
    }
}