package io.nepalpay.reactive.actuate;

import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

/**
 * Spring Boot Actuator {@link ReactiveHealthIndicator} for Khalti.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayKhalti</pre>
 *
 * <p>Returns {@code Mono<Health>} — fully non-blocking.
 *
 * <p>Design decision — config-only, no HTTP ping:
 * Health is determined by whether the client is correctly configured.
 * No live HTTP call is made to the Khalti API.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Sandbox rate limits would cause false DOWN during startup</li>
 *   <li>A live ping adds latency to every liveness/readiness probe</li>
 *   <li>A misconfigured client fails at startup — if bean exists,
 *       it is safe to call</li>
 *   <li>Real failures tracked via Micrometer counters</li>
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
public final class KhaltiReactiveHealthIndicator
        implements ReactiveHealthIndicator {

    private final KhaltiReactiveClient khaltiReactiveClient;

    /**
     * Creates a KhaltiReactiveHealthIndicator backed by the given client.
     *
     * @param khaltiReactiveClient auto-configured KhaltiReactiveClient bean
     */
    public KhaltiReactiveHealthIndicator(
            KhaltiReactiveClient khaltiReactiveClient) {
        this.khaltiReactiveClient = khaltiReactiveClient;
    }

    /**
     * Returns health status for the Khalti gateway — reactive.
     *
     * @return Mono of Health.up() with gateway details
     */
    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() ->
                Health.up()
                        .withDetail("gateway",    "Khalti")
                        .withDetail("mode",
                                khaltiReactiveClient.isSandbox()
                                        ? "SANDBOX" : "PRODUCTION")
                        .withDetail("url",
                                khaltiReactiveClient.baseUrl())
                        .withDetail("configured", true)
                        .build());
    }
}