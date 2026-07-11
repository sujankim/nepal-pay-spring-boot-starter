package io.nepalpay.actuate;

import io.nepalpay.connectips.ConnectIpsClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the ConnectIPS gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayConnectIps</pre>
 *
 * <p>Design decision — config-only, no HTTP ping.
 *
 * <p>Note: Spring Boot 4.1.0 — package is
 * {@code org.springframework.boot.health.contributor}.
 *
 * @author Sujan Lamichhane
 */
public final class ConnectIpsHealthIndicator implements HealthIndicator {

    private final ConnectIpsClient connectIpsClient;

    /**
     * Creates a ConnectIpsHealthIndicator backed by the given client.
     *
     * @param connectIpsClient auto-configured ConnectIpsClient bean
     */
    public ConnectIpsHealthIndicator(ConnectIpsClient connectIpsClient) {
        this.connectIpsClient = connectIpsClient;
    }

    /**
     * Returns health status for the ConnectIPS gateway.
     *
     * @return Health.up() with gateway details
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("gateway",    "ConnectIPS")
                .withDetail("mode",       connectIpsClient.isSandbox()
                        ? "UAT" : "PRODUCTION")
                .withDetail("formUrl",    connectIpsClient.formActionUrl())
                .withDetail("pfxLoaded",  true)
                .withDetail("configured", true)
                .build();
    }
}