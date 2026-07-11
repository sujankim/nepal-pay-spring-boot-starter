package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for EsewaMetrics.
 */
@DisplayName("EsewaMetrics")
class EsewaMetricsTest {

    private MeterRegistry registry;
    private EsewaMetrics  metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new EsewaMetrics(registry, true);
    }

    @Test
    @DisplayName("all timers and counters registered at construction")
    void allMetersRegistered() {
        assertThat(registry.find(EsewaMetrics.VERIFY_TIMER)
                .tag("status", "success").timer()).isNotNull();
        assertThat(registry.find(EsewaMetrics.VERIFY_TIMER)
                .tag("status", "error").timer()).isNotNull();
        assertThat(registry.find(EsewaMetrics.STATUS_TIMER)
                .tag("status", "success").timer()).isNotNull();
        assertThat(registry.find(EsewaMetrics.STATUS_TIMER)
                .tag("status", "error").timer()).isNotNull();
        assertThat(registry.find(EsewaMetrics.SIGNATURE_FAILED)
                .counter()).isNotNull();
        assertThat(registry.find(EsewaMetrics.RETRY_COUNTER)
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("recordVerify() success records in verify success timer")
    void recordVerify_success() {
        Timer timer = registry.find(EsewaMetrics.VERIFY_TIMER)
                .tag("status", "success").timer();
        metrics.recordVerify(() -> "ok");
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordVerify() error records in verify error timer")
    void recordVerify_error() {
        Timer timer = registry.find(EsewaMetrics.VERIFY_TIMER)
                .tag("status", "error").timer();
        assertThatThrownBy(() ->
                metrics.recordVerify(() -> {
                    throw new RuntimeException("sig fail");
                }));
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordStatus() success records in status success timer")
    void recordStatus_success() {
        Timer timer = registry.find(EsewaMetrics.STATUS_TIMER)
                .tag("status", "success").timer();
        metrics.recordStatus(() -> "ok");
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementSignatureFailed() increments counter")
    void incrementSignatureFailed() {
        Counter counter = registry.find(EsewaMetrics.SIGNATURE_FAILED)
                .counter();
        assertThat(counter.count()).isEqualTo(0);
        metrics.incrementSignatureFailed();
        assertThat(counter.count()).isEqualTo(1);
        metrics.incrementSignatureFailed();
        assertThat(counter.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementStatusRetry() increments counter")
    void incrementStatusRetry() {
        Counter counter = registry.find(EsewaMetrics.RETRY_COUNTER)
                .counter();
        metrics.incrementStatusRetry();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Timer accessors return non-null registered timers")
    void timerAccessors_returnRegisteredTimers() {
        assertThat(metrics.verifySuccessTimer()).isNotNull();
        assertThat(metrics.verifyErrorTimer()).isNotNull();
        assertThat(metrics.statusSuccessTimer()).isNotNull();
        assertThat(metrics.statusErrorTimer()).isNotNull();

        // Accessors return the SAME instance as registered
        Timer registered = registry.find(EsewaMetrics.VERIFY_TIMER)
                .tag("status", "success").timer();
        assertThat(metrics.verifySuccessTimer()).isSameAs(registered);
    }
}