package io.nepalpay.reactive.actuate;

import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

/**
 * Spring Boot Actuator {@link ReactiveHealthIndicator} for eSewa.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayEsewa</pre>
 *
 * <p>Returns {@code Mono<Health>} — fully non-blocking.
 * Design decision — config-only, no HTTP ping.
 *
 * <p>Example healthy response:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "gateway":    "eSewa",
 *     "mode":       "SANDBOX",
 *     "formUrl":    "https://rc-epay.esewa.com.np/api/epay/main/v2/form",
 *     "configured": true
 *   }
 * }
 * </pre>
 *
 * @author Sujan Lamichhane
 */
public final class EsewaReactiveHealthIndicator
        implements ReactiveHealthIndicator {

    private final EsewaReactiveClient esewaReactiveClient;

    /**
     * Creates an EsewaReactiveHealthIndicator backed by the given client.
     *
     * @param esewaReactiveClient auto-configured EsewaReactiveClient bean
     */
    public EsewaReactiveHealthIndicator(
            EsewaReactiveClient esewaReactiveClient) {
        this.esewaReactiveClient = esewaReactiveClient;
    }

    /**
     * Returns health status for the eSewa gateway — reactive.
     *
     * @return Mono of Health.up() with gateway details
     */
    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() ->
                Health.up()
                        .withDetail("gateway",    "eSewa")
                        .withDetail("mode",
                                esewaReactiveClient.isSandbox()
                                        ? "SANDBOX" : "PRODUCTION")
                        .withDetail("formUrl",
                                esewaReactiveClient.formActionUrl())
                        .withDetail("configured", true)
                        .build());
    }
}