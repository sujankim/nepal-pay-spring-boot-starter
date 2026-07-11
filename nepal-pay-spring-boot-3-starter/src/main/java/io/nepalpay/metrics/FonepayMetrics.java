package io.nepalpay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer metrics for Fonepay payment gateway operations.
 *
 * <p>Fonepay uses URL redirect and makes no server-to-server HTTP calls
 * — there is no latency to measure with a Timer.
 * Only event counters are recorded.
 *
 * <p><strong>Counters:</strong>
 * <ul>
 *   <li>{@code nepalpay.fonepay.redirect.built}
 *       — incremented each time a signed redirect URL is built</li>
 *   <li>{@code nepalpay.fonepay.callback.verified}
 *       — HMAC-SHA512 verified, tagged with status=success|failed</li>
 *   <li>{@code nepalpay.fonepay.callback.signature.failed}
 *       — HMAC-SHA512 signature mismatches detected</li>
 * </ul>
 *
 * <p><strong>Tags:</strong>
 * {@code gateway=fonepay}, {@code sandbox=true|false}
 *
 * @author Sujan Lamichhane
 */
public final class FonepayMetrics {

    // ── Metric names ──────────────────────────────────────────────────────
    public static final String REDIRECT_BUILT      =
            "nepalpay.fonepay.redirect.built";
    public static final String CALLBACK_VERIFIED   =
            "nepalpay.fonepay.callback.verified";
    public static final String SIGNATURE_FAILED    =
            "nepalpay.fonepay.callback.signature.failed";

    // ── Tag constants ─────────────────────────────────────────────────────
    private static final String TAG_GATEWAY  = "gateway";
    private static final String TAG_SANDBOX  = "sandbox";
    private static final String TAG_STATUS   = "status";
    private static final String GATEWAY_VAL  = "fonepay";

    // ── Pre-built counters ────────────────────────────────────────────────
    private final Counter redirectBuiltCounter;
    private final Counter callbackSuccessCounter;
    private final Counter callbackFailedCounter;
    private final Counter signatureFailedCounter;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Creates and registers all Fonepay metrics.
     *
     * @param registry MeterRegistry from Spring Boot Actuator
     * @param sandbox  true if operating in sandbox mode
     */
    public FonepayMetrics(MeterRegistry registry, boolean sandbox) {
        String sandboxTag = String.valueOf(sandbox);

        this.redirectBuiltCounter = Counter.builder(REDIRECT_BUILT)
                .description(
                        "Number of signed Fonepay redirect URLs built")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .register(registry);

        this.callbackSuccessCounter = Counter.builder(CALLBACK_VERIFIED)
                .description(
                        "Number of Fonepay callbacks verified successfully")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_STATUS,  "success")
                .register(registry);

        this.callbackFailedCounter = Counter.builder(CALLBACK_VERIFIED)
                .description(
                        "Number of Fonepay callbacks with failed payment status")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .tag(TAG_STATUS,  "failed")
                .register(registry);

        this.signatureFailedCounter = Counter.builder(SIGNATURE_FAILED)
                .description(
                        "Number of Fonepay HMAC-SHA512 signature verification " +
                                "failures — possible tampered callbacks")
                .tag(TAG_GATEWAY, GATEWAY_VAL)
                .tag(TAG_SANDBOX, sandboxTag)
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Increments the redirect built counter.
     * Call each time {@code buildRedirectParams()} succeeds.
     */
    public void incrementRedirectBuilt() {
        redirectBuiltCounter.increment();
    }

    /**
     * Increments the callback verified success counter.
     * Call when {@code verifyCallback()} passes HMAC and PS=success.
     */
    public void incrementCallbackSuccess() {
        callbackSuccessCounter.increment();
    }

    /**
     * Increments the callback verified failed counter.
     * Call when {@code verifyCallback()} passes HMAC but PS=failed.
     */
    public void incrementCallbackFailed() {
        callbackFailedCounter.increment();
    }

    /**
     * Increments the signature failure counter.
     * Call when HMAC-SHA512 verification fails in {@code verifyCallback()}.
     *
     * <p>A spike in this counter indicates a potential fraud attempt.
     * Configure a Grafana alert:
     * {@code rate(nepalpay_fonepay_callback_signature_failed_total[5m]) > 3}
     */
    public void incrementSignatureFailed() {
        signatureFailedCounter.increment();
    }
}