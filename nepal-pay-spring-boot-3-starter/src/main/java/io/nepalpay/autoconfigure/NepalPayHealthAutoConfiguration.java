package io.nepalpay.autoconfigure;

import io.nepalpay.actuate.ConnectIpsHealthIndicator;
import io.nepalpay.actuate.EsewaHealthIndicator;
import io.nepalpay.actuate.FonepayHealthIndicator;
import io.nepalpay.actuate.KhaltiHealthIndicator;
import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.fonepay.FonepayClient;
import io.nepalpay.khalti.KhaltiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * NepalPay Health Auto-Configuration — Spring Boot 3.
 *
 * <p>Activates only when <strong>both</strong> conditions are true:
 * <ol>
 *   <li>{@code spring-boot-starter-actuator} is on the classpath
 *       ({@code @ConditionalOnClass(HealthIndicator.class)})</li>
 *   <li>{@code nepalpay.health.enabled} is {@code true}
 *       (defaults to {@code true} — opt-out behaviour)</li>
 * </ol>
 *
 * <p>When active, registers one {@link HealthIndicator} per gateway
 * that has been configured. Each indicator is only created when its
 * corresponding gateway client bean exists in the context.
 *
 * <p><strong>Health endpoint structure:</strong>
 * <pre>
 * GET /actuator/health
 * {
 *   "status": "UP",
 *   "components": {
 *     "nepalpayKhalti": {
 *       "status": "UP",
 *       "details": {
 *         "gateway":    "Khalti",
 *         "mode":       "SANDBOX",
 *         "url":        "https://dev.khalti.com/api/v2",
 *         "configured": true
 *       }
 *     },
 *     "nepalpayEsewa": { ... },
 *     "nepalpayConnectIps": { ... },
 *     "nepalpayFonepay": { ... }
 *   }
 * }
 * </pre>
 *
 * <p><strong>Disable health indicators entirely:</strong>
 * <pre>
 * nepalpay:
 *   health:
 *     enabled: false
 * </pre>
 *
 * <p><strong>Expose health details in application.yml</strong>
 * (required to see {@code details} block in the response):
 * <pre>
 * management:
 *   endpoint:
 *     health:
 *       show-details: always
 *   endpoints:
 *     web:
 *       exposure:
 *         include: health
 * </pre>
 *
 * <p>Runs after {@link NepalPayAutoConfiguration} and
 * {@link NepalPayMetricsAutoConfiguration} so that gateway client beans
 * are guaranteed to exist before health indicators are wired.
 *
 * @author Sujan Lamichhane
 * @see KhaltiHealthIndicator
 * @see EsewaHealthIndicator
 * @see ConnectIpsHealthIndicator
 * @see FonepayHealthIndicator
 */
@Slf4j
@AutoConfiguration(after = {
        NepalPayAutoConfiguration.class,
        NepalPayMetricsAutoConfiguration.class
})
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(
        prefix      = "nepalpay.health",
        name        = "enabled",
        havingValue = "true",
        matchIfMissing = true)   // opt-out — active by default
public class NepalPayHealthAutoConfiguration {

    // ── Khalti ───────────────────────────────────────────────────────────

    /**
     * Registers {@link KhaltiHealthIndicator} when:
     * <ul>
     *   <li>A {@link KhaltiClient} bean exists in the context</li>
     *   <li>No existing {@code KhaltiHealthIndicator} bean
     *       (developer override wins)</li>
     * </ul>
     *
     * <p>Bean name {@code nepalpayKhalti} controls the key used in
     * the {@code /actuator/health} response. Spring Boot Actuator
     * derives the health key from the bean name by stripping the
     * {@code HealthIndicator} suffix when registering named beans.
     * Using an explicit bean name gives us a predictable, clean key.
     *
     * @param khaltiClient auto-configured KhaltiClient bean
     * @return KhaltiHealthIndicator
     */
    @Bean("nepalpayKhaltiHealthIndicator")
    @ConditionalOnMissingBean(KhaltiHealthIndicator.class)
    @ConditionalOnBean(KhaltiClient.class)
    public KhaltiHealthIndicator khaltiHealthIndicator(
            KhaltiClient khaltiClient) {

        log.info("[NepalPay Health] Registering KhaltiHealthIndicator" +
                " → /actuator/health/nepalpayKhaltiHealthIndicator");

        return new KhaltiHealthIndicator(khaltiClient);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * Registers {@link EsewaHealthIndicator} when:
     * <ul>
     *   <li>An {@link EsewaClient} bean exists in the context</li>
     *   <li>No existing {@code EsewaHealthIndicator} bean</li>
     * </ul>
     *
     * @param esewaClient auto-configured EsewaClient bean
     * @return EsewaHealthIndicator
     */
    @Bean("nepalpayEsewaHealthIndicator")
    @ConditionalOnMissingBean(EsewaHealthIndicator.class)
    @ConditionalOnBean(EsewaClient.class)
    public EsewaHealthIndicator esewaHealthIndicator(
            EsewaClient esewaClient) {

        log.info("[NepalPay Health] Registering EsewaHealthIndicator" +
                " → /actuator/health/nepalpayEsewaHealthIndicator");

        return new EsewaHealthIndicator(esewaClient);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * Registers {@link ConnectIpsHealthIndicator} when:
     * <ul>
     *   <li>A {@link ConnectIpsClient} bean exists in the context</li>
     *   <li>No existing {@code ConnectIpsHealthIndicator} bean</li>
     * </ul>
     *
     * <p>If the ConnectIPS .pfx was invalid, the {@link ConnectIpsClient}
     * bean would not exist (fail-fast constructor). So if this indicator
     * is registered, the .pfx is confirmed loaded and valid.
     *
     * @param connectIpsClient auto-configured ConnectIpsClient bean
     * @return ConnectIpsHealthIndicator
     */
    @Bean("nepalpayConnectIpsHealthIndicator")
    @ConditionalOnMissingBean(ConnectIpsHealthIndicator.class)
    @ConditionalOnBean(ConnectIpsClient.class)
    public ConnectIpsHealthIndicator connectIpsHealthIndicator(
            ConnectIpsClient connectIpsClient) {

        log.info("[NepalPay Health] Registering ConnectIpsHealthIndicator" +
                " → /actuator/health/nepalpayConnectIpsHealthIndicator");

        return new ConnectIpsHealthIndicator(connectIpsClient);
    }

    // ── Fonepay ───────────────────────────────────────────────────────────

    /**
     * Registers {@link FonepayHealthIndicator} when:
     * <ul>
     *   <li>A {@link FonepayClient} bean exists in the context</li>
     *   <li>No existing {@code FonepayHealthIndicator} bean</li>
     * </ul>
     *
     * @param fonepayClient auto-configured FonepayClient bean
     * @return FonepayHealthIndicator
     */
    @Bean("nepalpayFonepayHealthIndicator")
    @ConditionalOnMissingBean(FonepayHealthIndicator.class)
    @ConditionalOnBean(FonepayClient.class)
    public FonepayHealthIndicator fonepayHealthIndicator(
            FonepayClient fonepayClient) {

        log.info("[NepalPay Health] Registering FonepayHealthIndicator" +
                " → /actuator/health/nepalpayFonepayHealthIndicator");

        return new FonepayHealthIndicator(fonepayClient);
    }
}