package io.nepalpay.core.retry;

/**
 * Retry configuration for NepalPay gateway clients.
 *
 * <p>Controls exponential backoff retry behavior for HTTP calls
 * to Nepal payment gateways (Khalti, eSewa, ConnectIPS).
 *
 * <p><strong>Fonepay is not applicable.</strong>
 * Fonepay uses URL redirect — it makes no server-to-server HTTP calls,
 * so retry does not apply.
 *
 * <p><strong>Retry is disabled by default.</strong>
 * Enable it explicitly per gateway in your {@code application.yml}:
 * <pre>
 * nepalpay:
 *   khalti:
 *     retry:
 *       enabled: true
 *       max-attempts: 3
 *       initial-delay-ms: 500
 *       multiplier: 2.0
 *       max-delay-ms: 5000
 * </pre>
 *
 * <p><strong>What gets retried:</strong>
 * <ul>
 *   <li>✅ 5xx server errors — gateway temporarily unavailable</li>
 *   <li>✅ Network timeouts — connection reset, read timeout</li>
 * </ul>
 *
 * <p><strong>What never gets retried:</strong>
 * <ul>
 *   <li>❌ 4xx client errors — bad key, bad request (won't fix itself)</li>
 *   <li>❌ Signature failures — security alert, must not retry</li>
 *   <li>❌ Validation errors — null/blank input (won't fix itself)</li>
 * </ul>
 *
 * <p><strong>Exponential backoff with jitter:</strong>
 * <pre>
 * Attempt 1 fails → wait initialDelayMs     (e.g. 500ms)
 * Attempt 2 fails → wait 500 × multiplier   (e.g. 1000ms)
 * Attempt 3 fails → wait 1000 × multiplier  (e.g. 2000ms)
 *                   capped at maxDelayMs    (e.g. 5000ms)
 *
 * Jitter adds a small random offset (±10% of delay) to each wait.
 * This prevents all users from retrying simultaneously when a
 * gateway recovers from downtime — the "thundering herd" problem.
 * </pre>
 *
 * @param enabled        Whether retry is active. Default: {@code false}.
 *                       Must be explicitly set to {@code true} to enable.
 * @param maxAttempts    Maximum number of RETRY attempts after the first failure.
 *                       Total calls = maxAttempts + 1.
 *                       Example: maxAttempts=3 → up to 4 total calls.
 *                       Default: {@code 3}.
 * @param initialDelayMs Milliseconds to wait before the first retry.
 *                       Default: {@code 500} (half a second).
 * @param multiplier     Factor by which delay increases after each retry.
 *                       Must be >= 1.0. Use 1.0 for constant delay.
 *                       Default: {@code 2.0} (doubles each time).
 * @param maxDelayMs     Maximum milliseconds to wait between any two retries.
 *                       Caps the exponential growth. Default: {@code 5000}.
 *
 * Retry configuration for NepalPay gateway clients.
 * @author Sujan Lamichhane
 */
public record RetryProperties(
        boolean enabled,
        int maxAttempts,
        long initialDelayMs,
        double multiplier,
        long maxDelayMs
) {

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Default retry configuration — retry <strong>disabled</strong>.
     *
     * <p>Used by gateway clients when the user has not configured
     * a {@code retry:} block in their {@code application.yml}.
     *
     * <p>Values:
     * <ul>
     *   <li>enabled       = false</li>
     *   <li>maxAttempts   = 3</li>
     *   <li>initialDelayMs = 500</li>
     *   <li>multiplier    = 2.0</li>
     *   <li>maxDelayMs    = 5000</li>
     * </ul>
     */
    public static final RetryProperties DEFAULT = new RetryProperties(
            false,
            3,
            500L,
            2.0,
            5000L
    );

    /**
     * A disabled retry configuration with all-zero values.
     *
     * <p>Used internally when retry should be completely skipped
     * regardless of configuration.
     */
    public static final RetryProperties DISABLED = new RetryProperties(
            false, 0, 0L, 1.0, 0L
    );

    // ─────────────────────────────────────────────────────────────────────────
    // COMPACT CONSTRUCTOR — VALIDATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates retry configuration on construction.
     *
     * <p>Ensures all values are in safe ranges so client code
     * does not need to guard against invalid config at runtime.
     */
    public RetryProperties {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException(
                    "RetryProperties.maxAttempts must be >= 0. Got: "
                            + maxAttempts);
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException(
                    "RetryProperties.initialDelayMs must be >= 0. Got: "
                            + initialDelayMs);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException(
                    "RetryProperties.multiplier must be >= 1.0. Got: "
                            + multiplier
                            + ". Use 1.0 for constant delay.");
        }
        if (maxDelayMs < 0) {
            throw new IllegalArgumentException(
                    "RetryProperties.maxDelayMs must be >= 0. Got: "
                            + maxDelayMs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVENIENCE METHODS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if retry is actually active and meaningful.
     *
     * <p>Retry is active only when ALL of these are true:
     * <ul>
     *   <li>{@code enabled = true}</li>
     *   <li>{@code maxAttempts > 0}</li>
     * </ul>
     *
     * <p>Use this in client code instead of checking {@code enabled}
     * alone — it guards against a misconfiguration like
     * {@code enabled=true, max-attempts=0}.
     *
     * @return true if retry will actually be performed
     */
    public boolean isActive() {
        return enabled && maxAttempts > 0;
    }

    /**
     * Compute the next delay in milliseconds using exponential backoff.
     *
     * <p>Formula: {@code min(currentDelayMs × multiplier, maxDelayMs)}
     *
     * <p>Used by client retry loops to calculate how long to wait
     * before each successive retry attempt.
     *
     * @param currentDelayMs the delay used for the previous retry
     * @return next delay in milliseconds, capped at {@link #maxDelayMs}
     */
    public long nextDelay(long currentDelayMs) {
        long next = (long) (currentDelayMs * multiplier);
        return Math.min(next, maxDelayMs);
    }

    /**
     * Apply jitter to a delay value.
     *
     * <p>Adds a random offset of ±10% to the delay.
     * This prevents all clients from retrying at exactly the same
     * millisecond when a gateway recovers from downtime
     * (the "thundering herd" problem).
     *
     * <p>Example: jitter(500) → somewhere between 450ms and 550ms.
     *
     * @param delayMs base delay in milliseconds
     * @return delay with jitter applied, minimum 0ms
     */
    public static long jitter(long delayMs) {
        if (delayMs <= 0) return 0;
        // ±10% random jitter
        long jitterRange = (long) (delayMs * 0.1);
        long offset = (long) ((Math.random() * 2 * jitterRange) - jitterRange);
        return Math.max(0, delayMs + offset);
    }

    /**
     * Returns a human-readable summary of this retry configuration.
     * Useful for log messages during client initialization.
     *
     * @return summary string
     */
    public String summary() {
        if (!isActive()) {
            return "DISABLED";
        }
        return "enabled | maxAttempts=" + maxAttempts
                + " | initialDelay=" + initialDelayMs + "ms"
                + " | multiplier=" + multiplier
                + " | maxDelay=" + maxDelayMs + "ms";
    }
}