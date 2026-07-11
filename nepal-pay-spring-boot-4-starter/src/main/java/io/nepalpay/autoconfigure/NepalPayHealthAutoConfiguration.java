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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * NepalPay Health Auto-Configuration — Spring Boot 4.
 *
 * <p>Spring Boot 4.1.0 moved health classes from
 * {@code org.springframework.boot.actuate.health} (Boot 3) to
 * {@code org.springframework.boot.health.contributor} (Boot 4).
 * The classes are in the {@code spring-boot-health} module.
 *
 * <p>Activates only when:
 * <ol>
 *   <li>{@code spring-boot-health} is on the classpath
 *       ({@code @ConditionalOnClass(HealthIndicator.class)})</li>
 *   <li>{@code nepalpay.health.enabled} is {@code true} (default)</li>
 * </ol>
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
        NepalPayAutoConfiguration.class,
        NepalPayMetricsAutoConfiguration.class
})
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(
        prefix      = "nepalpay.health",
        name        = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NepalPayHealthAutoConfiguration {

    /**
     * Registers {@link KhaltiHealthIndicator}.
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

    /**
     * Registers {@link EsewaHealthIndicator}.
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

    /**
     * Registers {@link ConnectIpsHealthIndicator}.
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

    /**
     * Registers {@link FonepayHealthIndicator}.
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