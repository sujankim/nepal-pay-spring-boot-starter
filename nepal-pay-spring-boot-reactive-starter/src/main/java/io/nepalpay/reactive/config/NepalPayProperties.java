package io.nepalpay.reactive.config;

import io.nepalpay.core.retry.RetryProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Root configuration properties for NepalPay Reactive Starter.
 *
 * <p>Identical structure to the blocking starter — same YAML configuration
 * works for both. Only the artifact dependency changes.
 *
 * <p>Add to your {@code application.yml}:
 * <pre>
 * nepalpay:
 *   khalti:
 *     secret-key: ${KHALTI_SECRET_KEY}
 *     return-url:  ${KHALTI_RETURN_URL}
 *     sandbox: true
 *     retry:
 *       enabled: true
 *       max-attempts: 3
 *       initial-delay-ms: 500
 *       multiplier: 2.0
 *       max-delay-ms: 5000
 *
 *   metrics:
 *     enabled: true    # opt-out — disable with false
 *
 *   health:
 *     enabled: true    # opt-out — disable with false
 * </pre>
 *
 * @param khalti     Khalti payment gateway configuration
 * @param esewa      eSewa payment gateway configuration
 * @param connectips ConnectIPS payment gateway configuration
 * @param fonepay    Fonepay payment gateway configuration
 * @param metrics    Micrometer metrics configuration (null = enabled by default)
 * @param health     Actuator health indicator configuration (null = enabled by default)
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties     khalti,
        EsewaProperties      esewa,
        ConnectIpsProperties connectips,
        FonepayProperties    fonepay,
        MetricsProperties    metrics,
        HealthProperties     health
) {

    public record KhaltiProperties(
            String secretKey,
            String returnUrl,
            String websiteUrl,
            @DefaultValue("true")  boolean sandbox,
            @DefaultValue("10")    int timeoutSeconds,
            RetryProperties retry
    ) {
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    public record EsewaProperties(
            String secretKey,
            String productCode,
            String successUrl,
            String failureUrl,
            @DefaultValue("true")  boolean sandbox,
            @DefaultValue("10")    int timeoutSeconds,
            RetryProperties retry
    ) {
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    public record ConnectIpsProperties(
            int merchantId,
            String appId,
            String appName,
            String appPassword,
            String pfxPath,
            String pfxPassword,
            @DefaultValue("true") boolean sandbox,
            RetryProperties retry
    ) {
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    public record FonepayProperties(
            String merchantCode,
            String secretKey,
            String returnUrl,
            @DefaultValue("true") boolean sandbox
    ) {}

    /**
     * Micrometer metrics configuration.
     *
     * <p>Metrics are opt-out — enabled by default when Actuator is on
     * the classpath. Set {@code nepalpay.metrics.enabled=false} to disable.
     *
     * <p><strong>Note:</strong> This record component is {@code null} when
     * the {@code nepalpay.metrics:} block is absent from YAML entirely.
     * Use {@link NepalPayProperties#isMetricsEnabled()} for null-safe access.
     *
     * @param enabled true (default) = record metrics when Actuator present
     */
    public record MetricsProperties(
            @DefaultValue("true") boolean enabled
    ) {}

    /**
     * Actuator health indicator configuration.
     *
     * <p>Health indicators are opt-out — enabled by default when Actuator
     * is on the classpath. Set {@code nepalpay.health.enabled=false} to
     * disable all NepalPay {@code /actuator/health} indicators.
     *
     * <p><strong>Note:</strong> This record component is {@code null} when
     * the {@code nepalpay.health:} block is absent from YAML entirely.
     * Use {@link NepalPayProperties#isHealthEnabled()} for null-safe access.
     *
     * @param enabled true (default) = register health indicators
     */
    public record HealthProperties(
            @DefaultValue("true") boolean enabled
    ) {}

    /**
     * Returns true if Micrometer metrics are enabled.
     *
     * <p>Safe to call even when the {@code nepalpay.metrics:} block is
     * absent from {@code application.yml} — returns {@code true} in that case.
     *
     * @return true if metrics should be recorded (default when block absent)
     */
    public boolean isMetricsEnabled() {
        return metrics == null || metrics.enabled();
    }

    /**
     * Returns true if Actuator health indicators are enabled.
     *
     * <p>Safe to call even when the {@code nepalpay.health:} block is
     * absent from {@code application.yml} — returns {@code true} in that case.
     *
     * @return true if health indicators should be registered (default when absent)
     */
    public boolean isHealthEnabled() {
        return health == null || health.enabled();
    }
}