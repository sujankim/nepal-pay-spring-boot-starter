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
 * Unit tests for ConnectIpsMetrics.
 */
@DisplayName("ConnectIpsMetrics")
class ConnectIpsMetricsTest {

    private MeterRegistry    registry;
    private ConnectIpsMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new ConnectIpsMetrics(registry, true);
    }

    @Test
    @DisplayName("all timers and counters registered at construction")
    void allMetersRegistered() {
        assertThat(registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("status", "success").timer()).isNotNull();
        assertThat(registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("status", "error").timer()).isNotNull();
        assertThat(registry.find(ConnectIpsMetrics.RETRY_COUNTER)
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("timers tagged with gateway=connectips")
    void timersTaggedWithGateway() {
        assertThat(registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("gateway", "connectips").timer()).isNotNull();
    }

    @Test
    @DisplayName("recordValidate() success records in validate success timer")
    void recordValidate_success() {
        Timer timer = registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("status", "success").timer();
        metrics.recordValidate(() -> "ok");
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordValidate() error records in validate error timer and rethrows")
    void recordValidate_error() {
        Timer timer = registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("status", "error").timer();
        assertThatThrownBy(() ->
                metrics.recordValidate(() -> {
                    throw new RuntimeException("bank timeout");
                }))
                .hasMessage("bank timeout");
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementValidateRetry() increments counter")
    void incrementValidateRetry() {
        Counter counter = registry.find(ConnectIpsMetrics.RETRY_COUNTER)
                .counter();
        metrics.incrementValidateRetry();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Timer accessors return non-null registered timers")
    void timerAccessors_returnRegisteredTimers() {
        assertThat(metrics.validateSuccessTimer()).isNotNull();
        assertThat(metrics.validateErrorTimer()).isNotNull();

        Timer registered = registry.find(ConnectIpsMetrics.VALIDATE_TIMER)
                .tag("status", "success").timer();
        assertThat(metrics.validateSuccessTimer()).isSameAs(registered);
    }
}