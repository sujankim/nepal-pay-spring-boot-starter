package io.nepalpay.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer metrics for ConnectIPS payment gateway operations.
 *
 * <p>Records the following metrics:
 *
 * <p><strong>Timers:</strong>
 * <ul>
 *   <li>{@code nepalpay.connectips.validate.duration}</li>
 * </ul>
 *
 * <p><strong>Counters:</strong>
 * <ul>
 *   <li>{@code nepalpay.connectips.retry.attempts}</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
public final class ConnectIpsMetrics {

    // ── Metric names ──────────────────────────────────────────────────────
    public static final String VALIDATE_TIMER =
            "nepalpay.connectips.validate.duration";
    public static final String RETRY_COUNTER  =
            "nepalpay.connectips.retry.attempts";

    // ── Tag constants ─────────────────────────────────────────────────────
    private static final String TAG_GATEWAY  = "gateway";
    private static final String TAG_SANDBOX  = "sandbox";
    private static final String TAG_STATUS   = "status";
    private static final String TAG_OP       = "operation";
    private static final String GATEWAY_VAL  = "connectips";
    private static final String SUCCESS      = "success";
    private static final String ERROR        = "error";

    // ── Pre-built meters ──────────────────────────────────────────────────
    private final Timer   validateSuccessTimer;
    private final Timer   validateErrorTimer;
    private final Counter validateRetryCounter;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Creates and registers all ConnectIPS metrics.
     *
     * @param registry MeterRegistry from Spring Boot Actuator
     * @param sandbox  true if operating in UAT mode
     */
    public ConnectIpsMetrics(MeterRegistry registry, boolean sandbox) {
        String sandboxTag = String.valueOf(sandbox);

        this.validateSuccessTimer = Timer.builder(VALIDATE_TIMER)
                .description(
                        "Time taken to validate a ConnectIPS transaction successfully")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "validate")
                .tag(TAG_STATUS,  SUCCESS)
                .register(registry);

        this.validateErrorTimer = Timer.builder(VALIDATE_TIMER)
                .description(
                        "Time taken for a failed ConnectIPS transaction validation")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "validate")
                .tag(TAG_STATUS,  ERROR)
                .register(registry);

        this.validateRetryCounter = Counter.builder(RETRY_COUNTER)
                .description(
                        "Number of retry attempts for ConnectIPS validateTransaction()")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "validate")
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — blocking record methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Records the duration of a {@code validateTransaction()} call.
     *
     * @param operation the validate operation to time
     * @param <T>       return type
     * @return result from the operation
     */
    public <T> T recordValidate(Supplier<T> operation) {
        long start = System.nanoTime();
        try {
            T result = operation.get();
            validateSuccessTimer.record(
                    System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            validateErrorTimer.record(
                    System.nanoTime() - start, TimeUnit.NANOSECONDS);
            throw e;
        }
    }

    /**
     * Increments the retry counter for {@code validateTransaction()}.
     */
    public void incrementValidateRetry() {
        validateRetryCounter.increment();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — Timer accessors for reactive timing
    // Used by ConnectIpsReactiveClient via Timer.Sample + doOnSuccess/doOnError
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the Timer for successful {@code validateTransaction()} calls.
     *
     * @return success Timer for validate operations
     */
    public Timer validateSuccessTimer() { return validateSuccessTimer; }

    /**
     * Returns the Timer for failed {@code validateTransaction()} calls.
     *
     * @return error Timer for validate operations
     */
    public Timer validateErrorTimer()   { return validateErrorTimer; }
}