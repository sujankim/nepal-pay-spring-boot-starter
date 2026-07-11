package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer metrics for Khalti payment gateway operations.
 *
 * <p>Records the following metrics:
 *
 * <p><strong>Timers (latency + throughput):</strong>
 * <ul>
 *   <li>{@code nepalpay.khalti.payment.initiate.duration}</li>
 *   <li>{@code nepalpay.khalti.payment.lookup.duration}</li>
 *   <li>{@code nepalpay.khalti.payment.refund.duration}</li>
 * </ul>
 *
 * <p><strong>Counters (events):</strong>
 * <ul>
 *   <li>{@code nepalpay.khalti.retry.attempts}</li>
 * </ul>
 *
 * <p><strong>Tags applied to all metrics:</strong>
 * <ul>
 *   <li>{@code gateway=khalti}</li>
 *   <li>{@code sandbox=true|false}</li>
 *   <li>{@code status=success|error} (on timers)</li>
 *   <li>{@code operation=initiate|lookup|refund}</li>
 * </ul>
 *
 * <p>Metrics are recorded when this helper is constructed with a
 * {@link io.micrometer.core.instrument.MeterRegistry} — typically
 * injected via Spring Boot Actuator. When no {@code MeterRegistry}
 * is provided, this class is not instantiated and no metrics are
 * recorded. Full auto-configuration wiring is coming in v1.2.0.
 *
 * @author Sujan Lamichhane
 */
public final class KhaltiMetrics {

    // ── Metric names ──────────────────────────────────────────────────────
    public static final String INITIATE_TIMER = "nepalpay.khalti.payment.initiate.duration";
    public static final String LOOKUP_TIMER   = "nepalpay.khalti.payment.lookup.duration";
    public static final String REFUND_TIMER   = "nepalpay.khalti.payment.refund.duration";
    public static final String RETRY_COUNTER  = "nepalpay.khalti.retry.attempts";

    // ── Tag names ─────────────────────────────────────────────────────────
    private static final String TAG_GATEWAY  = "gateway";
    private static final String TAG_SANDBOX  = "sandbox";
    private static final String TAG_STATUS   = "status";
    private static final String TAG_OP       = "operation";

    // ── Tag values ────────────────────────────────────────────────────────
    private static final String GATEWAY_VALUE = "khalti";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR   = "error";

    // ── Fields ────────────────────────────────────────────────────────────
    private final MeterRegistry registry;
    private final String        sandboxTag;

    // ── Pre-built timers (success path) ───────────────────────────────────
    private final Timer initiateSuccessTimer;
    private final Timer lookupSuccessTimer;
    private final Timer refundSuccessTimer;

    // ── Pre-built timers (error path) ─────────────────────────────────────
    private final Timer initiateErrorTimer;
    private final Timer lookupErrorTimer;
    private final Timer refundErrorTimer;

    // ── Pre-built retry counters ──────────────────────────────────────────
    private final Counter initiateRetryCounter;
    private final Counter lookupRetryCounter;
    private final Counter refundRetryCounter;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Creates and registers all Khalti metrics with the given registry.
     *
     * <p>Pre-building timers and counters at construction time is the
     * Micrometer best practice — avoids per-call registry lookups and
     * ensures metrics appear in the registry immediately at startup
     * rather than only after first use.
     *
     * @param registry MeterRegistry from Spring Boot Actuator
     * @param sandbox  true if operating in sandbox mode
     */
    public KhaltiMetrics(MeterRegistry registry, boolean sandbox) {
        this.registry   = registry;
        this.sandboxTag = String.valueOf(sandbox);

        // ── Initiate timers ───────────────────────────────────────────────
        this.initiateSuccessTimer = buildTimer(
                INITIATE_TIMER, "initiate", STATUS_SUCCESS,
                "Time taken to initiate a Khalti payment successfully");
        this.initiateErrorTimer = buildTimer(
                INITIATE_TIMER, "initiate", STATUS_ERROR,
                "Time taken for a failed Khalti payment initiation");

        // ── Lookup timers ─────────────────────────────────────────────────
        this.lookupSuccessTimer = buildTimer(
                LOOKUP_TIMER, "lookup", STATUS_SUCCESS,
                "Time taken to lookup a Khalti payment successfully");
        this.lookupErrorTimer = buildTimer(
                LOOKUP_TIMER, "lookup", STATUS_ERROR,
                "Time taken for a failed Khalti payment lookup");

        // ── Refund timers ─────────────────────────────────────────────────
        this.refundSuccessTimer = buildTimer(
                REFUND_TIMER, "refund", STATUS_SUCCESS,
                "Time taken to process a Khalti refund successfully");
        this.refundErrorTimer = buildTimer(
                REFUND_TIMER, "refund", STATUS_ERROR,
                "Time taken for a failed Khalti refund");

        // ── Retry counters ────────────────────────────────────────────────
        this.initiateRetryCounter = buildCounter(
                RETRY_COUNTER, "initiate",
                "Number of retry attempts for Khalti initiatePayment()");
        this.lookupRetryCounter = buildCounter(
                RETRY_COUNTER, "lookup",
                "Number of retry attempts for Khalti lookupPayment()");
        this.refundRetryCounter = buildCounter(
                RETRY_COUNTER, "refund",
                "Number of retry attempts for Khalti refundPayment()");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — record methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Records the duration of a {@code initiatePayment()} call.
     * Executes the supplier, records time, tags with success or error.
     *
     * @param operation the payment operation to time
     * @param <T>       return type
     * @return result from the operation
     * @throws RuntimeException if the operation throws
     */
    public <T> T recordInitiate(Supplier<T> operation) {
        return record(operation, initiateSuccessTimer, initiateErrorTimer);
    }

    /**
     * Records the duration of a {@code lookupPayment()} call.
     *
     * @param operation the payment operation to time
     * @param <T>       return type
     * @return result from the operation
     */
    public <T> T recordLookup(Supplier<T> operation) {
        return record(operation, lookupSuccessTimer, lookupErrorTimer);
    }

    /**
     * Records the duration of a {@code refundPayment()} call.
     *
     * @param operation the refund operation to time
     * @param <T>       return type
     * @return result from the operation
     */
    public <T> T recordRefund(Supplier<T> operation) {
        return record(operation, refundSuccessTimer, refundErrorTimer);
    }

    /**
     * Increments the retry counter for {@code initiatePayment()}.
     * Call once per retry attempt — not once per method call.
     */
    public void incrementInitiateRetry() {
        initiateRetryCounter.increment();
    }

    /**
     * Increments the retry counter for {@code lookupPayment()}.
     */
    public void incrementLookupRetry() {
        lookupRetryCounter.increment();
    }

    /**
     * Increments the retry counter for {@code refundPayment()}.
     */
    public void incrementRefundRetry() {
        refundRetryCounter.increment();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Execute the given supplier and record its duration.
     * Tags success timer on normal return, error timer on exception.
     */
    private <T> T record(
            Supplier<T> operation,
            Timer successTimer,
            Timer errorTimer) {

        long start = System.nanoTime();
        try {
            T result = operation.get();
            successTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            errorTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            throw e;
        }
    }

    /**
     * Build and register a Timer with standard NepalPay tags.
     */
    private Timer buildTimer(
            String name, String operation,
            String status, String description) {

        return Timer.builder(name)
                .description(description)
                .tag(TAG_GATEWAY, GATEWAY_VALUE)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      operation)
                .tag(TAG_STATUS,  status)
                .register(registry);
    }

    /**
     * Build and register a Counter with standard NepalPay tags.
     */
    private Counter buildCounter(
            String name, String operation, String description) {

        return Counter.builder(name)
                .description(description)
                .tag(TAG_GATEWAY, GATEWAY_VALUE)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      operation)
                .register(registry);
    }
}