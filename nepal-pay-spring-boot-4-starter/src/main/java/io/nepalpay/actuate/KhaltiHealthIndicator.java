package io.nepalpay.actuate;

import io.nepalpay.khalti.KhaltiClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Khalti gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayKhaltiHealthIndicator</pre>
 *
 * <p>Design decision — config-only, no HTTP ping.
 *
 * <p>Note: Spring Boot 4.1.0 moved health classes to the new
 * {@code org.springframework.boot.health.contributor} package
 * inside the {@code spring-boot-health} module.
 * Boot 3 uses {@code org.springframework.boot.actuate.health}.
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
     * @return Health.up() with gateway details
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