package io.nepalpay.actuate;

import io.nepalpay.fonepay.FonepayClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Fonepay gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayFonepay</pre>
 *
 * <p><strong>Design decision — config-only, no HTTP ping.</strong>
 * See {@link KhaltiHealthIndicator} for the full rationale.
 *
 * <p>Fonepay uses a URL redirect model — it makes no server-side HTTP calls.
 * This means there is nothing to ping even if we wanted to. Health is
 * determined purely by whether the client is correctly configured.
 *
 * <p>Example healthy response:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "gateway":    "Fonepay",
 *     "mode":       "SANDBOX",
 *     "gatewayUrl": "https://dev.fonepay.com/api/merchantRequest",
 *     "note":       "URL redirect — no server-side HTTP calls",
 *     "configured": true
 *   }
 * }
 * </pre>
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
     * <p>Status is {@code UP} when:
     * <ul>
     *   <li>The {@link FonepayClient} bean exists (secret-key configured)</li>
     *   <li>The client was successfully initialized at startup</li>
     * </ul>
     *
     * <p>Fonepay makes zero server-side HTTP calls — the URL redirect model
     * means there is no API endpoint to ping. Correct configuration is the
     * only health signal available.
     *
     * @return {@code Health.up()} with gateway details
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