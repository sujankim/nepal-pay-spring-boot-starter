package io.nepalpay.autoconfigure;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.khalti.KhaltiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * NepalPay Spring Boot Auto-Configuration.
 *
 * <p>Automatically loaded by Spring Boot 4.1.0 from:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *
 * <p>Beans created:
 * <ul>
 *   <li>{@link KhaltiClient} — when {@code nepalpay.khalti.secret-key} is present</li>
 *   <li>{@link EsewaClient}  — when {@code nepalpay.esewa.secret-key} is present</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NepalPayProperties.class)
public class NepalPayAutoConfiguration {

    // ── Khalti ───────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link KhaltiClient} when:
     * <ol>
     *   <li>{@code nepalpay.khalti.secret-key} property is present</li>
     *   <li>No {@link KhaltiClient} bean already defined by the developer</li>
     * </ol>
     *
     * @param properties        bound from nepalpay.* in application.yml
     * @param restClientBuilder Spring Boot's pre-configured RestClient builder
     * @return configured KhaltiClient bean
     */
    @Bean
    @ConditionalOnMissingBean(KhaltiClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.khalti", name = "secret-key")
    public KhaltiClient khaltiClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder) {

        log.info("[NepalPay] Auto-configuring KhaltiClient | sandbox={}",
                properties.khalti().sandbox());

        return new KhaltiClient(properties.khalti(), restClientBuilder);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link EsewaClient} when:
     * <ol>
     *   <li>{@code nepalpay.esewa.secret-key} property is present</li>
     *   <li>No {@link EsewaClient} bean already defined by the developer</li>
     * </ol>
     *
     * <p>Note: EsewaClient creates its own {@code JsonMapper} locally
     * where needed — no ObjectMapper injection required.
     *
     * @param properties        bound from nepalpay.* in application.yml
     * @param restClientBuilder Spring Boot's pre-configured RestClient builder
     * @return configured EsewaClient bean
     */
    @Bean
    @ConditionalOnMissingBean(EsewaClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.esewa", name = "secret-key")
    public EsewaClient esewaClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder) {

        log.info("[NepalPay] Auto-configuring EsewaClient | sandbox={} | productCode={}",
                properties.esewa().sandbox(),
                properties.esewa().productCode());

        return new EsewaClient(properties.esewa(), restClientBuilder);
    }
}