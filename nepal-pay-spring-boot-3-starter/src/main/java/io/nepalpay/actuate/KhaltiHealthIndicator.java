package io.nepalpay.actuate;

import io.nepalpay.khalti.KhaltiClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Khalti gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayKhalti</pre>
 *
 * <p><strong>Design decision — config-only, no HTTP ping:</strong>
 * Health is determined by whether the client is correctly configured
 * and initialized. No live HTTP call is made to the Khalti API.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Sandbox rate limits would cause false DOWN states during startup</li>
 *   <li>A live ping adds latency to every liveness/readiness probe cycle</li>
 *   <li>A misconfigured client fails at startup (missing secret key) —
 *       if the bean exists, it is safe to call</li>
 *   <li>Real payment failures are better tracked via Micrometer counters
 *       ({@code nepalpay.khalti.payment.*.duration{status=error}})</li>
 * </ul>
 *
 * <p>Example healthy response:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "gateway":    "Khalti",
 *     "mode":       "SANDBOX",
 *     "url":        "https://dev.khalti.com/api/v2",
 *     "configured": true
 *   }
 * }
 * </pre>
 *
 * @author Sujan Lamichhane
 */
public final class KhaltiHealthIndicator implements HealthIndicator {

    private final KhaltiClient khaltiClient;

    /**
     * Creates a KhaltiHealthIndicator backed by the given client.
     *
     * @param khaltiClient auto-configured KhaltiClient bean
     */
    public KhaltiHealthIndicator(KhaltiClient khaltiClient) {
        this.khaltiClient = khaltiClient;
    }

    /**
     * Returns health status for the Khalti gateway.
     *
     * <p>Status is {@code UP} when:
     * <ul>
     *   <li>The {@link KhaltiClient} bean exists (secret key was configured)</li>
     *   <li>The client was successfully initialized at startup</li>
     * </ul>
     *
     * <p>The bean only exists when {@code nepalpay.khalti.secret-key} is set
     * — the {@code @ConditionalOnProperty} in auto-configuration ensures this.
     * If the bean does not exist, this indicator is not registered at all
     * and the key simply does not appear in the health response.
     *
     * @return {@code Health.up()} with gateway details
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("gateway",    "Khalti")
                .withDetail("mode",       khaltiClient.isSandbox()
                        ? "SANDBOX" : "PRODUCTION")
                .withDetail("url",        khaltiClient.baseUrl())
                .withDetail("configured", true)
                .build();
    }
}