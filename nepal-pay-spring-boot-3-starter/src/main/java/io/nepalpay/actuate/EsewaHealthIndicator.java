package io.nepalpay.actuate;

import io.nepalpay.esewa.EsewaClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the eSewa gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayEsewa</pre>
 *
 * <p><strong>Design decision — config-only, no HTTP ping.</strong>
 * See {@link KhaltiHealthIndicator} for the full rationale.
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
public final class EsewaHealthIndicator implements HealthIndicator {

    private final EsewaClient esewaClient;

    /**
     * Creates an EsewaHealthIndicator backed by the given client.
     *
     * @param esewaClient auto-configured EsewaClient bean
     */
    public EsewaHealthIndicator(EsewaClient esewaClient) {
        this.esewaClient = esewaClient;
    }

    /**
     * Returns health status for the eSewa gateway.
     *
     * <p>Status is {@code UP} when:
     * <ul>
     *   <li>The {@link EsewaClient} bean exists (secret key was configured)</li>
     *   <li>The client was successfully initialized at startup</li>
     * </ul>
     *
     * @return {@code Health.up()} with gateway details
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("gateway",    "eSewa")
                .withDetail("mode",       esewaClient.isSandbox()
                        ? "SANDBOX" : "PRODUCTION")
                .withDetail("formUrl",    esewaClient.formActionUrl())
                .withDetail("configured", true)
                .build();
    }
}