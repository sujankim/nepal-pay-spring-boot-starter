package io.nepalpay.config;

import io.nepalpay.core.retry.RetryProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Root configuration properties for NepalPay Spring Boot Starter.
 *
 * <p>Add to your {@code application.yml}:
 * <pre>
 * nepalpay:
 *   khalti:
 *     secret-key: ${KHALTI_SECRET_KEY}
 *     return-url:  ${KHALTI_RETURN_URL}
 *     website-url: ${YOUR_WEBSITE_URL}
 *     sandbox: true
 *     retry:
 *       enabled: false
 *
 *   esewa:
 *     secret-key:   ${ESEWA_SECRET_KEY}
 *     product-code: ${ESEWA_PRODUCT_CODE}
 *     success-url:  ${ESEWA_SUCCESS_URL}
 *     failure-url:  ${ESEWA_FAILURE_URL}
 *     sandbox: true
 *
 *   connectips:
 *     merchant-id:  ${CONNECTIPS_MERCHANT_ID}
 *     app-id:       ${CONNECTIPS_APP_ID}
 *     app-name:     ${CONNECTIPS_APP_NAME}
 *     app-password: ${CONNECTIPS_APP_PASSWORD}
 *     pfx-path:     ${CONNECTIPS_PFX_PATH}
 *     pfx-password: ${CONNECTIPS_PFX_PASSWORD}
 *     sandbox: true
 *
 *   fonepay:
 *     merchant-code: ${FONEPAY_MERCHANT_CODE}
 *     secret-key:    ${FONEPAY_SECRET_KEY}
 *     return-url:    ${FONEPAY_RETURN_URL}
 *     sandbox: true
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
 * @param metrics    Micrometer metrics configuration
 * @param health     Actuator health indicator configuration
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

    // ── Khalti ───────────────────────────────────────────────────────────

    /**
     * Khalti payment gateway configuration.
     *
     * @param secretKey      Your Khalti secret key
     * @param returnUrl      URL Khalti redirects to after payment
     * @param websiteUrl     Your merchant website URL
     * @param sandbox        true = dev.khalti.com / false = khalti.com
     * @param timeoutSeconds HTTP timeout in seconds
     * @param retry          Retry configuration — null = defaults (disabled)
     */
    public record KhaltiProperties(
            String secretKey,
            String returnUrl,
            String websiteUrl,
            @DefaultValue("true")  boolean sandbox,
            @DefaultValue("10")    int timeoutSeconds,
            RetryProperties retry
    ) {
        /**
         * Returns retry configuration, falling back to
         * {@link RetryProperties#DEFAULT} when not configured.
         *
         * @return retry config, never null
         */
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * eSewa payment gateway configuration.
     *
     * @param secretKey      eSewa HMAC-SHA256 secret key
     * @param productCode    Sandbox: EPAYTEST / Production: your merchant code
     * @param successUrl     URL eSewa redirects to on success
     * @param failureUrl     URL eSewa redirects to on failure
     * @param sandbox        true = sandbox / false = production
     * @param timeoutSeconds HTTP timeout in seconds
     * @param retry          Retry configuration — null = defaults (disabled)
     */
    public record EsewaProperties(
            String secretKey,
            String productCode,
            String successUrl,
            String failureUrl,
            @DefaultValue("true")  boolean sandbox,
            @DefaultValue("10")    int timeoutSeconds,
            RetryProperties retry
    ) {
        /**
         * Returns retry configuration, falling back to
         * {@link RetryProperties#DEFAULT} when not configured.
         *
         * @return retry config, never null
         */
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * ConnectIPS payment gateway configuration.
     *
     * @param merchantId  NCHL-assigned merchant ID
     * @param appId       Application ID from NCHL
     * @param appName     Application name from NCHL
     * @param appPassword Application password for HTTP Basic Auth
     * @param pfxPath     Path to CREDITOR.pfx (Spring Resource format)
     * @param pfxPassword Password for the CREDITOR.pfx file
     * @param sandbox     true = UAT / false = production
     * @param retry       Retry configuration — null = defaults (disabled)
     */
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
        /**
         * Returns retry configuration, falling back to
         * {@link RetryProperties#DEFAULT} when not configured.
         *
         * @return retry config, never null
         */
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    // ── Fonepay ───────────────────────────────────────────────────────────

    /**
     * Fonepay payment gateway configuration.
     *
     * <p>No retry — Fonepay makes no server-side HTTP calls.
     *
     * @param merchantCode Your Fonepay merchant code (PID)
     * @param secretKey    Your Fonepay HMAC-SHA512 secret key
     * @param returnUrl    URL Fonepay redirects to after payment
     * @param sandbox      true = dev.fonepay.com / false = fonepay.com
     */
    public record FonepayProperties(
            String merchantCode,
            String secretKey,
            String returnUrl,
            @DefaultValue("true") boolean sandbox
    ) {}

    // ── Metrics ───────────────────────────────────────────────────────────

    /**
     * Micrometer metrics configuration.
     *
     * <p>Metrics are opt-out — enabled by default when Actuator is on
     * the classpath. Set {@code nepalpay.metrics.enabled=false} to disable
     * all NepalPay Micrometer timers and counters.
     *
     * <p>YAML:
     * <pre>
     * nepalpay:
     *   metrics:
     *     enabled: false
     * </pre>
     *
     * @param enabled true (default) = record metrics when Actuator present
     */
    public record MetricsProperties(
            @DefaultValue("true") boolean enabled
    ) {}

    // ── Health ────────────────────────────────────────────────────────────

    /**
     * Actuator health indicator configuration.
     *
     * <p>Health indicators are opt-out — enabled by default when Actuator
     * is on the classpath. Set {@code nepalpay.health.enabled=false} to
     * disable all NepalPay {@code /actuator/health} indicators.
     *
     * <p>YAML:
     * <pre>
     * nepalpay:
     *   health:
     *     enabled: false
     * </pre>
     *
     * @param enabled true (default) = register health indicators
     */
    public record HealthProperties(
            @DefaultValue("true") boolean enabled
    ) {}

    // ── Null-safe accessors ───────────────────────────────────────────────

    /**
     * Returns true if Micrometer metrics are enabled.
     *
     * <p><strong>Why this exists:</strong>
     * Spring Boot record binding returns {@code null} for the
     * {@code metrics} component when the {@code nepalpay.metrics:} block
     * is absent from {@code application.yml} entirely.
     * {@code @DefaultValue("true")} on the {@code enabled} field only
     * applies when the block IS present but the field is omitted.
     * This method handles the absent-block case safely.
     *
     * <p>Use this instead of {@code metrics().enabled()} directly.
     *
     * @return true if metrics should be recorded (default when block absent)
     */
    public boolean isMetricsEnabled() {
        return metrics == null || metrics.enabled();
    }

    /**
     * Returns true if Actuator health indicators are enabled.
     *
     * <p><strong>Why this exists:</strong>
     * Spring Boot record binding returns {@code null} for the
     * {@code health} component when the {@code nepalpay.health:} block
     * is absent from {@code application.yml} entirely.
     * This method handles the absent-block case safely.
     *
     * <p>Use this instead of {@code health().enabled()} directly.
     *
     * @return true if health indicators should be registered (default when absent)
     */
    public boolean isHealthEnabled() {
        return health == null || health.enabled();
    }
}