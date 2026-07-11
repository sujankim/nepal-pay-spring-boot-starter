package io.nepalpay.actuate;

import io.nepalpay.esewa.EsewaClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the eSewa gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayEsewa</pre>
 *
 * <p>Design decision — config-only, no HTTP ping.
 *
 * <p>Note: Spring Boot 4.1.0 — package is
 * {@code org.springframework.boot.health.contributor}.
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
     * @return Health.up() with gateway details
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