package io.nepalpay.actuate;

import io.nepalpay.connectips.ConnectIpsClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the ConnectIPS gateway.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayConnectIps</pre>
 *
 * <p><strong>Design decision — config-only, no HTTP ping.</strong>
 * See {@link KhaltiHealthIndicator} for the full rationale.
 *
 * <p>ConnectIPS additionally reports {@code pfxLoaded: true} — meaning
 * the CREDITOR.pfx RSA private key was successfully loaded at startup.
 * If the .pfx was invalid or missing, the bean would not exist
 * (ConnectIpsClient fails fast in the constructor).
 *
 * <p>Example healthy response:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "gateway":    "ConnectIPS",
 *     "mode":       "UAT",
 *     "formUrl":    "https://uat.connectips.com/connectipswebgw/loginpage",
 *     "pfxLoaded":  true,
 *     "configured": true
 *   }
 * }
 * </pre>
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
     * <p>Status is {@code UP} when:
     * <ul>
     *   <li>The {@link ConnectIpsClient} bean exists (merchant-id configured)</li>
     *   <li>The CREDITOR.pfx was successfully loaded at startup (fail-fast)</li>
     *   <li>The RSA private key was extracted and cached successfully</li>
     * </ul>
     *
     * <p>{@code pfxLoaded: true} confirms the RSA private key is cached and
     * ready — if the .pfx was invalid, startup would have failed already.
     *
     * @return {@code Health.up()} with gateway details
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