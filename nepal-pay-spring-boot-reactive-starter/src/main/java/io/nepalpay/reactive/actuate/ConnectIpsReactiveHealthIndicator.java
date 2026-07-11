package io.nepalpay.reactive.actuate;

import io.nepalpay.reactive.connectips.ConnectIpsReactiveClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

/**
 * Spring Boot Actuator {@link ReactiveHealthIndicator} for ConnectIPS.
 *
 * <p>Exposed at:
 * <pre>GET /actuator/health/nepalpayConnectIps</pre>
 *
 * <p>Returns {@code Mono<Health>} — fully non-blocking.
 * Design decision — config-only, no HTTP ping.
 *
 * <p>ConnectIPS additionally reports {@code pfxLoaded: true} — meaning
 * the CREDITOR.pfx RSA private key was successfully loaded at startup.
 * If the .pfx was invalid or missing, the bean would not exist.
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
public final class ConnectIpsReactiveHealthIndicator
        implements ReactiveHealthIndicator {

    private final ConnectIpsReactiveClient connectIpsReactiveClient;

    /**
     * Creates a ConnectIpsReactiveHealthIndicator backed by the given client.
     *
     * @param connectIpsReactiveClient auto-configured ConnectIpsReactiveClient
     */
    public ConnectIpsReactiveHealthIndicator(
            ConnectIpsReactiveClient connectIpsReactiveClient) {
        this.connectIpsReactiveClient = connectIpsReactiveClient;
    }

    /**
     * Returns health status for the ConnectIPS gateway — reactive.
     *
     * @return Mono of Health.up() with gateway details
     */
    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() ->
                Health.up()
                        .withDetail("gateway",   "ConnectIPS")
                        .withDetail("mode",
                                connectIpsReactiveClient.isSandbox()
                                        ? "UAT" : "PRODUCTION")
                        .withDetail("formUrl",
                                connectIpsReactiveClient.formActionUrl())
                        .withDetail("pfxLoaded",  true)
                        .withDetail("configured", true)
                        .build());
    }
}