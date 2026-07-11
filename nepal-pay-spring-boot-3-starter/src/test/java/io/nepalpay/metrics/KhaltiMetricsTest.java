package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for KhaltiMetrics.
 * Uses SimpleMeterRegistry — no Spring context needed.
 */
@DisplayName("KhaltiMetrics")
class KhaltiMetricsTest {

    private MeterRegistry registry;
    private KhaltiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new KhaltiMetrics(registry, true); // sandbox=true
    }

    // ── Timer registration ────────────────────────────────────────────────

    @Nested
    @DisplayName("Timer registration")
    class TimerRegistration {

        @Test
        @DisplayName("all 6 timers registered at construction")
        void allTimersRegistered() {
            // Timers are registered immediately — not lazily on first use
            assertThat(registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "success").timer()).isNotNull();
            assertThat(registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "error").timer()).isNotNull();
            assertThat(registry.find(KhaltiMetrics.LOOKUP_TIMER)
                    .tag("status", "success").timer()).isNotNull();
            assertThat(registry.find(KhaltiMetrics.LOOKUP_TIMER)
                    .tag("status", "error").timer()).isNotNull();
            assertThat(registry.find(KhaltiMetrics.REFUND_TIMER)
                    .tag("status", "success").timer()).isNotNull();
            assertThat(registry.find(KhaltiMetrics.REFUND_TIMER)
                    .tag("status", "error").timer()).isNotNull();
        }

        @Test
        @DisplayName("timers tagged with gateway=khalti")
        void timersTaggedWithGateway() {
            assertThat(registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("gateway", "khalti").timer()).isNotNull();
        }

        @Test
        @DisplayName("timers tagged with sandbox=true")
        void timersTaggedWithSandbox() {
            assertThat(registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("sandbox", "true").timer()).isNotNull();
        }
    }

    // ── recordInitiate() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("recordInitiate()")
    class RecordInitiate {

        @Test
        @DisplayName("success path records in success timer")
        void success_recordedInSuccessTimer() {
            Timer before = registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "success").timer();
            assertThat(before).isNotNull();
            assertThat(before.count()).isEqualTo(0);

            metrics.recordInitiate(() -> "ok");

            assertThat(before.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("error path records in error timer and rethrows")
        void error_recordedInErrorTimerAndRethrows() {
            Timer errorTimer = registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "error").timer();

            assertThatThrownBy(() ->
                    metrics.recordInitiate(() -> {
                        throw new RuntimeException("gateway down");
                    }))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("gateway down");

            assertThat(errorTimer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("success does NOT record in error timer")
        void success_doesNotRecordInErrorTimer() {
            Timer errorTimer = registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "error").timer();

            metrics.recordInitiate(() -> "ok");

            assertThat(errorTimer.count()).isEqualTo(0);
        }
    }

    // ── recordLookup() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("recordLookup()")
    class RecordLookup {

        @Test
        @DisplayName("success path records in lookup success timer")
        void success_recordedInSuccessTimer() {
            Timer timer = registry.find(KhaltiMetrics.LOOKUP_TIMER)
                    .tag("status", "success").timer();
            metrics.recordLookup(() -> "ok");
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("error records in lookup error timer")
        void error_recordedInErrorTimer() {
            Timer timer = registry.find(KhaltiMetrics.LOOKUP_TIMER)
                    .tag("status", "error").timer();

            assertThatThrownBy(() ->
                    metrics.recordLookup(() -> {
                        throw new RuntimeException("lookup error");
                    }));

            assertThat(timer.count()).isEqualTo(1);
        }
    }

    // ── recordRefund() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("recordRefund()")
    class RecordRefund {

        @Test
        @DisplayName("success path records in refund success timer")
        void success_recordedInSuccessTimer() {
            Timer timer = registry.find(KhaltiMetrics.REFUND_TIMER)
                    .tag("status", "success").timer();
            metrics.recordRefund(() -> "ok");
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    // ── Retry counters ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry counters")
    class RetryCounters {

        @Test
        @DisplayName("incrementInitiateRetry increments counter")
        void incrementInitiateRetry() {
            Counter counter = registry.find(KhaltiMetrics.RETRY_COUNTER)
                    .tag("operation", "initiate").counter();
            assertThat(counter.count()).isEqualTo(0);
            metrics.incrementInitiateRetry();
            assertThat(counter.count()).isEqualTo(1);
            metrics.incrementInitiateRetry();
            assertThat(counter.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("incrementLookupRetry increments counter")
        void incrementLookupRetry() {
            Counter counter = registry.find(KhaltiMetrics.RETRY_COUNTER)
                    .tag("operation", "lookup").counter();
            metrics.incrementLookupRetry();
            assertThat(counter.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("incrementRefundRetry increments counter")
        void incrementRefundRetry() {
            Counter counter = registry.find(KhaltiMetrics.RETRY_COUNTER)
                    .tag("operation", "refund").counter();
            metrics.incrementRefundRetry();
            assertThat(counter.count()).isEqualTo(1);
        }
    }

    // ── Timer accessors for reactive ──────────────────────────────────────

    @Nested
    @DisplayName("Timer accessors for reactive")
    class TimerAccessors {

        @Test
        @DisplayName("initiateSuccessTimer() returns non-null Timer")
        void initiateSuccessTimer_notNull() {
            assertThat(metrics.initiateSuccessTimer()).isNotNull();
        }

        @Test
        @DisplayName("initiateErrorTimer() returns non-null Timer")
        void initiateErrorTimer_notNull() {
            assertThat(metrics.initiateErrorTimer()).isNotNull();
        }

        @Test
        @DisplayName("lookupSuccessTimer() returns non-null Timer")
        void lookupSuccessTimer_notNull() {
            assertThat(metrics.lookupSuccessTimer()).isNotNull();
        }

        @Test
        @DisplayName("lookupErrorTimer() returns non-null Timer")
        void lookupErrorTimer_notNull() {
            assertThat(metrics.lookupErrorTimer()).isNotNull();
        }

        @Test
        @DisplayName("refundSuccessTimer() returns non-null Timer")
        void refundSuccessTimer_notNull() {
            assertThat(metrics.refundSuccessTimer()).isNotNull();
        }

        @Test
        @DisplayName("refundErrorTimer() returns non-null Timer")
        void refundErrorTimer_notNull() {
            assertThat(metrics.refundErrorTimer()).isNotNull();
        }

        @Test
        @DisplayName("timer accessors return same instance as registered timers")
        void timerAccessors_returnRegisteredTimers() {
            Timer registered = registry.find(KhaltiMetrics.INITIATE_TIMER)
                    .tag("status", "success").timer();
            assertThat(metrics.initiateSuccessTimer()).isSameAs(registered);
        }
    }

    // ── Production mode ───────────────────────────────────────────────────

    @Test
    @DisplayName("sandbox=false tags timers with sandbox=false")
    void productionMode_sandboxFalse() {
        MeterRegistry prodRegistry = new SimpleMeterRegistry();
        KhaltiMetrics prodMetrics  = new KhaltiMetrics(prodRegistry, false);

        assertThat(prodRegistry.find(KhaltiMetrics.INITIATE_TIMER)
                .tag("sandbox", "false").timer()).isNotNull();
        assertThat(prodRegistry.find(KhaltiMetrics.INITIATE_TIMER)
                .tag("sandbox", "true").timer()).isNull();
    }
}