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
 *   <li>{@code nepalpay.esewa.callback.verify.duration}
 *       — latency of {@code verifyCallback()}</li>
 *   <li>{@code nepalpay.esewa.status.check.duration}
 *       — latency of {@code checkStatus()}</li>
 * </ul>
 *
 * <p><strong>Counters:</strong>
 * <ul>
 *   <li>{@code nepalpay.esewa.callback.signature.failed}
 *       — HMAC-SHA256 signature mismatches detected</li>
 *   <li>{@code nepalpay.esewa.retry.attempts}
 *       — retry attempts on {@code checkStatus()}</li>
 * </ul>
 *
 * <p><strong>Tags:</strong>
 * {@code gateway=esewa}, {@code sandbox=true|false},
 * {@code status=success|error}
 *
 * @author Sujan Lamichhane
 */
public final class EsewaMetrics {

    // ── Metric names ──────────────────────────────────────────────────────
    public static final String VERIFY_TIMER       =
            "nepalpay.esewa.callback.verify.duration";
    public static final String STATUS_TIMER        =
            "nepalpay.esewa.status.check.duration";
    public static final String SIGNATURE_FAILED    =
            "nepalpay.esewa.callback.signature.failed";
    public static final String RETRY_COUNTER       =
            "nepalpay.esewa.retry.attempts";

    // ── Tag constants ─────────────────────────────────────────────────────
    private static final String TAG_GATEWAY  = "gateway";
    private static final String TAG_SANDBOX  = "sandbox";
    private static final String TAG_STATUS   = "status";
    private static final String TAG_OP       = "operation";
    private static final String GATEWAY_VAL  = "esewa";
    private static final String SUCCESS      = "success";
    private static final String ERROR        = "error";

    // ── Fields ────────────────────────────────────────────────────────────
    private final MeterRegistry registry;
    private final String        sandboxTag;

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
        this.registry   = registry;
        this.sandboxTag = String.valueOf(sandbox);

        this.verifySuccessTimer = buildTimer(
                VERIFY_TIMER, "verify", SUCCESS,
                "Time taken to verify an eSewa callback successfully");
        this.verifyErrorTimer = buildTimer(
                VERIFY_TIMER, "verify", ERROR,
                "Time taken for a failed eSewa callback verification");

        this.statusSuccessTimer = buildTimer(
                STATUS_TIMER, "status", SUCCESS,
                "Time taken to check eSewa payment status successfully");
        this.statusErrorTimer = buildTimer(
                STATUS_TIMER, "status", ERROR,
                "Time taken for a failed eSewa status check");

        this.signatureFailedCounter = Counter.builder(SIGNATURE_FAILED)
                .description(
                        "Number of eSewa HMAC-SHA256 signature verification " +
                                "failures — possible tampered callbacks")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .register(registry);

        this.statusRetryCounter = Counter.builder(RETRY_COUNTER)
                .description(
                        "Number of retry attempts for eSewa checkStatus()")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      "status")
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
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
     * Call when HMAC-SHA256 verification fails in {@code verifyCallback()}.
     *
     * <p>A spike in this counter indicates a potential fraud attempt —
     * configure an alert in Grafana:
     * {@code rate(nepalpay_esewa_callback_signature_failed_total[5m]) > 5}
     */
    public void incrementSignatureFailed() {
        signatureFailedCounter.increment();
    }

    /**
     * Increments the status check retry counter.
     */
    public void incrementStatusRetry() {
        statusRetryCounter.increment();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

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

    private Timer buildTimer(
            String name, String operation,
            String status, String description) {

        return Timer.builder(name)
                .description(description)
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_OP,      operation)
                .tag(TAG_STATUS,  status)
                .register(registry);
    }
}