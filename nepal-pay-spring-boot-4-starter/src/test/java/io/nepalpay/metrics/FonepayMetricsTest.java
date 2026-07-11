package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FonepayMetrics.
 * Fonepay has no Timers — only event counters.
 */
@DisplayName("FonepayMetrics")
class FonepayMetricsTest {

    private MeterRegistry registry;
    private FonepayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new FonepayMetrics(registry, true);
    }

    @Test
    @DisplayName("all counters registered at construction")
    void allCountersRegistered() {
        assertThat(registry.find(FonepayMetrics.REDIRECT_BUILT)
                .counter()).isNotNull();
        assertThat(registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "success").counter()).isNotNull();
        assertThat(registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "failed").counter()).isNotNull();
        assertThat(registry.find(FonepayMetrics.SIGNATURE_FAILED)
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("counters tagged with gateway=fonepay")
    void countersTaggedWithGateway() {
        assertThat(registry.find(FonepayMetrics.REDIRECT_BUILT)
                .tag("gateway", "fonepay").counter()).isNotNull();
    }

    @Test
    @DisplayName("incrementRedirectBuilt() increments counter")
    void incrementRedirectBuilt() {
        Counter counter = registry.find(FonepayMetrics.REDIRECT_BUILT)
                .counter();
        metrics.incrementRedirectBuilt();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementCallbackSuccess() increments success counter")
    void incrementCallbackSuccess() {
        Counter counter = registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "success").counter();
        metrics.incrementCallbackSuccess();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementCallbackFailed() increments failed counter")
    void incrementCallbackFailed() {
        Counter counter = registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "failed").counter();
        metrics.incrementCallbackFailed();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementSignatureFailed() increments counter")
    void incrementSignatureFailed() {
        Counter counter = registry.find(FonepayMetrics.SIGNATURE_FAILED)
                .counter();
        assertThat(counter.count()).isEqualTo(0);
        metrics.incrementSignatureFailed();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("success and failed counters are independent")
    void successAndFailedCounters_areIndependent() {
        metrics.incrementCallbackSuccess();
        metrics.incrementCallbackSuccess();
        metrics.incrementCallbackFailed();

        assertThat(registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "success").counter().count()).isEqualTo(2);
        assertThat(registry.find(FonepayMetrics.CALLBACK_VERIFIED)
                .tag("status", "failed").counter().count()).isEqualTo(1);
    }
}