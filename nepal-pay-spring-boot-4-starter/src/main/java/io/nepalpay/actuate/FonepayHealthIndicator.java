package io.nepalpay.actuate;

import io.nepalpay.fonepay.FonepayClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Fonepay gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayFonepay</pre>
 *
 * <p>Design decision — config-only, no HTTP ping.
 * Fonepay makes no server-side HTTP calls — URL redirect only.
 *
 * <p>Note: Spring Boot 4.1.0 — package is
 * {@code org.springframework.boot.health.contributor}.
 *
 * @author Sujan Lamichhane
 */
public final class FonepayHealthIndicator implements HealthIndicator {

    private final FonepayClient fonepayClient;

    /**
     * Creates a FonepayHealthIndicator backed by the given client.
     *
     * @param fonepayClient auto-configured FonepayClient bean
     */
    public FonepayHealthIndicator(FonepayClient fonepayClient) {
        this.fonepayClient = fonepayClient;
    }

    /**
     * Returns health status for the Fonepay gateway.
     *
     * @return Health.up() with gateway details
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("gateway",    "Fonepay")
                .withDetail("mode",       fonepayClient.isSandbox()
                        ? "SANDBOX" : "PRODUCTION")
                .withDetail("gatewayUrl", fonepayClient.gatewayUrl())
                .withDetail("note",
                        "URL redirect — no server-side HTTP calls")
                .withDetail("configured", true)
                .build();
    }
}