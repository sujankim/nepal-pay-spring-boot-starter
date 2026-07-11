package io.nepalpay.reactive.autoconfigure;

import io.nepalpay.reactive.actuate.ConnectIpsReactiveHealthIndicator;
import io.nepalpay.reactive.actuate.EsewaReactiveHealthIndicator;
import io.nepalpay.reactive.actuate.KhaltiReactiveHealthIndicator;
import io.nepalpay.reactive.connectips.ConnectIpsReactiveClient;
import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * NepalPay Reactive Health Auto-Configuration.
 *
 * <p>Registers {@link ReactiveHealthIndicator} beans for each configured
 * gateway — non-blocking {@code Mono<Health>} responses via
 * {@code Mono.fromCallable()}.
 *
 * <p>Activates only when:
 * <ol>
 *   <li>{@code spring-boot-starter-actuator} is on the classpath
 *       ({@code @ConditionalOnClass(ReactiveHealthIndicator.class)})</li>
 *   <li>{@code nepalpay.health.enabled} is {@code true} (default)</li>
 * </ol>
 *
 * <p>Health endpoint structure:
 * <pre>
 * GET /actuator/health
 * {
 *   "components": {
 *     "nepalpayKhalti":     { "status": "UP", "details": { ... } },
 *     "nepalpayEsewa":      { "status": "UP", "details": { ... } },
 *     "nepalpayConnectIps": { "status": "UP", "details": { ... } }
 *   }
 * }
 * </pre>
 *
 * <p>Note: Fonepay uses URL redirect — no HTTP calls. Use the blocking
 * {@code FonepayClient} from the blocking starter in reactive apps.
 * No reactive health indicator is needed for Fonepay.
 *
 * <p>Disable health indicators:
 * <pre>
 * nepalpay:
 *   health:
 *     enabled: false
 * </pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration(after = {
        NepalPayReactiveAutoConfiguration.class,
        NepalPayReactiveMetricsAutoConfiguration.class
})
@ConditionalOnClass(ReactiveHealthIndicator.class)
@ConditionalOnProperty(
        prefix      = "nepalpay.health",
        name        = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NepalPayReactiveHealthAutoConfiguration {

    // ── Khalti ────────────────────────────────────────────────────────────

    /**
     * Registers {@link KhaltiReactiveHealthIndicator} when:
     * <ul>
     *   <li>A {@link KhaltiReactiveClient} bean exists</li>
     *   <li>No existing {@code KhaltiReactiveHealthIndicator} bean</li>
     * </ul>
     *
     * <p>Bean name {@code nepalpayKhaltiHealthIndicator} maps to
     * {@code /actuator/health/nepalpayKhalti} — Spring Boot strips the
     * {@code HealthIndicator} suffix automatically.
     *
     * @param khaltiReactiveClient auto-configured client bean
     * @return KhaltiReactiveHealthIndicator
     */
    @Bean("nepalpayKhaltiHealthIndicator")
    @ConditionalOnMissingBean(KhaltiReactiveHealthIndicator.class)
    @ConditionalOnBean(KhaltiReactiveClient.class)
    public KhaltiReactiveHealthIndicator khaltiReactiveHealthIndicator(
            KhaltiReactiveClient khaltiReactiveClient) {

        log.info("[NepalPay Reactive Health] Registering" +
                " KhaltiReactiveHealthIndicator" +
                " → /actuator/health/nepalpayKhalti");
        return new KhaltiReactiveHealthIndicator(khaltiReactiveClient);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * Registers {@link EsewaReactiveHealthIndicator} when:
     * <ul>
     *   <li>An {@link EsewaReactiveClient} bean exists</li>
     *   <li>No existing {@code EsewaReactiveHealthIndicator} bean</li>
     * </ul>
     *
     * @param esewaReactiveClient auto-configured client bean
     * @return EsewaReactiveHealthIndicator
     */
    @Bean("nepalpayEsewaHealthIndicator")
    @ConditionalOnMissingBean(EsewaReactiveHealthIndicator.class)
    @ConditionalOnBean(EsewaReactiveClient.class)
    public EsewaReactiveHealthIndicator esewaReactiveHealthIndicator(
            EsewaReactiveClient esewaReactiveClient) {

        log.info("[NepalPay Reactive Health] Registering" +
                " EsewaReactiveHealthIndicator" +
                " → /actuator/health/nepalpayEsewa");
        return new EsewaReactiveHealthIndicator(esewaReactiveClient);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * Registers {@link ConnectIpsReactiveHealthIndicator} when:
     * <ul>
     *   <li>A {@link ConnectIpsReactiveClient} bean exists</li>
     *   <li>No existing {@code ConnectIpsReactiveHealthIndicator} bean</li>
     * </ul>
     *
     * <p>If the .pfx was invalid, the bean would not exist (fail-fast).
     * So if this indicator is registered, pfxLoaded is always true.
     *
     * @param connectIpsReactiveClient auto-configured client bean
     * @return ConnectIpsReactiveHealthIndicator
     */
    @Bean("nepalpayConnectIpsHealthIndicator")
    @ConditionalOnMissingBean(ConnectIpsReactiveHealthIndicator.class)
    @ConditionalOnBean(ConnectIpsReactiveClient.class)
    public ConnectIpsReactiveHealthIndicator connectIpsReactiveHealthIndicator(
            ConnectIpsReactiveClient connectIpsReactiveClient) {

        log.info("[NepalPay Reactive Health] Registering" +
                " ConnectIpsReactiveHealthIndicator" +
                " → /actuator/health/nepalpayConnectIps");
        return new ConnectIpsReactiveHealthIndicator(connectIpsReactiveClient);
    }

    // ── Fonepay — no reactive health indicator ────────────────────────────
    // Fonepay uses URL redirect — makes no server-side HTTP calls.
    // Use FonepayClient (blocking) from blocking starter in reactive apps.
    // The blocking FonepayClient health indicator from Boot 3 starter
    // handles Fonepay health if both starters are present.
}