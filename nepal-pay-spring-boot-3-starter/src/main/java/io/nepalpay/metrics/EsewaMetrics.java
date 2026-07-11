package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer metrics for eSewa payment gateway operations.
 *
 * <p>Records the following metrics:
 *
 * <p><strong>Timers:</strong>
 * <ul>
 *   <li>{@code nepalpay.esewa.callback.verify.duration}</li>
 *   <li>{@code nepalpay.esewa.status.check.duration}</li>
 * </ul>
 *
 * <p><strong>Counters:</strong>
 * <ul>
 *   <li>{@code nepalpay.esewa.callback.signature.failed}</li>
 *   <li>{@code nepalpay.esewa.retry.attempts}</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
public final class EsewaMetrics {

    // ── Metric names ──────────────────────────────────────────────────────
    public static final String VERIFY_TIMER    =
            "nepalpay.esewa.callback.verify.duration";
    public static final String STATUS_TIMER    =
            "nepalpay.esewa.status.check.duration";
    public static final String SIGNATURE_FAILED =
            "nepalpay.esewa.callback.signature.failed";
    public static final String RETRY_COUNTER   =
            "nepalpay.esewa.retry.attempts";

    // ── Tag constants ─────────────────────────────────────────────────────
    private static final String TAG_GATEWAY  = "gateway";
    private static final String TAG_SANDBOX  = "sandbox";
    private static final String TAG_STATUS   = "status";
    private static final String TAG_OP       = "operation";
    private static final String GATEWAY_VAL  = "esewa";
    private static final String SUCCESS      = "success";
    private static final String ERROR        = "error";

    // ── Pre-built meters ──────────────────────────────────────────────────
    private final Timer   verifySuccessTimer;
    private final Timer   verifyErrorTimer;
    private final Timer   statusSuccessTimer;
    private final Timer   statusErrorTimer;
    private final Counter signatureFailedCounter;
    private final Counter statusRetryCounter;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Creates and registers all eSewa metrics.
     *
     * @param registry MeterRegistry from Spring Boot Actuator
     * @param sandbox  true if operating in sandbox mode
     */
    public EsewaMetrics(MeterRegistry registry, boolean sandbox) {
        String sandboxTag = String.valueOf(sandbox);

        this.verifySuccessTimer = Timer.builder(VERIFY_TIMER)
                .description("Time taken to verify an eSewa callback successfully")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "verify")
                .tag(TAG_STATUS,  SUCCESS)
                .register(registry);

        this.verifyErrorTimer = Timer.builder(VERIFY_TIMER)
                .description("Time taken for a failed eSewa callback verification")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "verify")
                .tag(TAG_STATUS,  ERROR)
                .register(registry);

        this.statusSuccessTimer = Timer.builder(STATUS_TIMER)
                .description("Time taken to check eSewa status successfully")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "status")
                .tag(TAG_STATUS,  SUCCESS)
                .register(registry);

        this.statusErrorTimer = Timer.builder(STATUS_TIMER)
                .description("Time taken for a failed eSewa status check")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "status")
                .tag(TAG_STATUS,  ERROR)
                .register(registry);

        this.signatureFailedCounter = Counter.builder(SIGNATURE_FAILED)
                .description(
                        "Number of eSewa HMAC-SHA256 signature verification " +
                                "failures — possible tampered callbacks")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .register(registry);

        this.statusRetryCounter = Counter.builder(RETRY_COUNTER)
                .description("Number of retry attempts for eSewa checkStatus()")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "status")
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — blocking record methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Records the duration of a {@code verifyCallback()} call.
     *
     * @param operation the verify operation to time
     * @param <T>       return type
     * @return result from the operation
     */
    public <T> T recordVerify(Supplier<T> operation) {
        return record(operation, verifySuccessTimer, verifyErrorTimer);
    }

    /**
     * Records the duration of a {@code checkStatus()} call.
     *
     * @param operation the status check operation to time
     * @param <T>       return type
     * @return result from the operation
     */
    public <T> T recordStatus(Supplier<T> operation) {
        return record(operation, statusSuccessTimer, statusErrorTimer);
    }

    /**
     * Increments the signature failure counter.
     * Call when HMAC-SHA256 verification fails.
     */
    public void incrementSignatureFailed() {
        signatureFailedCounter.increment();
    }

    /**
     * Increments the status retry counter.
     */
    public void incrementStatusRetry() {
        statusRetryCounter.increment();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — Timer accessors for reactive timing
    // Used by EsewaReactiveClient via Timer.Sample + doOnSuccess/doOnError
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the Timer for successful {@code verifyCallback()} calls.
     *
     * @return success Timer for verify operations
     */
    public Timer verifySuccessTimer() { return verifySuccessTimer; }

    /**
     * Returns the Timer for failed {@code verifyCallback()} calls.
     *
     * @return error Timer for verify operations
     */
    public Timer verifyErrorTimer()   { return verifyErrorTimer; }

    /**
     * Returns the Timer for successful {@code checkStatus()} calls.
     *
     * @return success Timer for status check operations
     */
    public Timer statusSuccessTimer() { return statusSuccessTimer; }

    /**
     * Returns the Timer for failed {@code checkStatus()} calls.
     *
     * @return error Timer for status check operations
     */
    public Timer statusErrorTimer()   { return statusErrorTimer; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────────────────

    private <T> T record(
            Supplier<T> operation,
            Timer successTimer,
            Timer errorTimer) {

        long start = System.nanoTime();
        try {
            T result = operation.get();
            successTimer.record(
                    System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            errorTimer.record(
                    System.nanoTime() - start, TimeUnit.NANOSECONDS);
            throw e;
        }
    }
}