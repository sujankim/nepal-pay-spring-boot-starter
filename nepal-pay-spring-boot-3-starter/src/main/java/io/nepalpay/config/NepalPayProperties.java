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
 *       enabled: false            # opt-in, off by default
 *       max-attempts: 3
 *       initial-delay-ms: 500
 *       multiplier: 2.0
 *       max-delay-ms: 5000
 *
 *   esewa:
 *     secret-key:   ${ESEWA_SECRET_KEY}
 *     product-code: ${ESEWA_PRODUCT_CODE}
 *     success-url:  ${ESEWA_SUCCESS_URL}
 *     failure-url:  ${ESEWA_FAILURE_URL}
 *     sandbox: true
 *     retry:
 *       enabled: false
 *
 *   connectips:
 *     merchant-id:  ${CONNECTIPS_MERCHANT_ID}
 *     app-id:       ${CONNECTIPS_APP_ID}
 *     app-name:     ${CONNECTIPS_APP_NAME}
 *     app-password: ${CONNECTIPS_APP_PASSWORD}
 *     pfx-path:     ${CONNECTIPS_PFX_PATH}
 *     pfx-password: ${CONNECTIPS_PFX_PASSWORD}
 *     sandbox: true
 *     retry:
 *       enabled: false
 *
 *   fonepay:
 *     merchant-code: ${FONEPAY_MERCHANT_CODE}
 *     secret-key:    ${FONEPAY_SECRET_KEY}
 *     return-url:    ${FONEPAY_RETURN_URL}
 *     sandbox: true
 *     # ← no retry block — Fonepay makes no HTTP calls (URL redirect only)
 * </pre>
 *
 * @param khalti     Khalti payment gateway configuration
 * @param esewa      eSewa payment gateway configuration
 * @param connectips ConnectIPS payment gateway configuration
 * @param fonepay    Fonepay payment gateway configuration
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties     khalti,
        EsewaProperties      esewa,
        ConnectIpsProperties connectips,
        FonepayProperties    fonepay,
        MetricsProperties    metrics
) {

    // ── Khalti ───────────────────────────────────────────────────────────────

    /**
     * Khalti payment gateway configuration.
     *
     * <p>Retry applies to:
     * {@code initiatePayment()}, {@code lookupPayment()},
     * {@code refundPayment()}.
     *
     * @param secretKey      Your Khalti secret key from merchant dashboard
     * @param returnUrl      URL Khalti redirects to after payment
     * @param websiteUrl     Your merchant website URL
     * @param sandbox        true = dev.khalti.com / false = khalti.com
     * @param timeoutSeconds HTTP timeout in seconds for Khalti API calls
     * @param retry          Retry configuration — null means use defaults
     *                       (retry disabled by default)
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
         * <p>Always use this instead of {@link #retry()} directly
         * in client code so you never get a NullPointerException
         * when the user omits the {@code retry:} block.
         *
         * @return retry config, never null
         */
        public RetryProperties retryOrDefault() {
            return retry != null ? retry : RetryProperties.DEFAULT;
        }
    }

    // ── eSewa ─────────────────────────────────────────────────────────────────

    /**
     * eSewa payment gateway configuration.
     *
     * <p>Retry applies to: {@code checkStatus()} (called inside
     * {@code verifyCallback()}).
     *
     * <p>Sandbox credentials:
     * Secret key: {@code 8gBm/:&amp;EnhH.1/q} |
     * Product code: {@code EPAYTEST}
     *
     * @param secretKey      eSewa HMAC-SHA256 secret key
     * @param productCode    Sandbox: EPAYTEST / Production: your merchant code
     * @param successUrl     URL eSewa redirects to on successful payment
     * @param failureUrl     URL eSewa redirects to on failed payment
     * @param sandbox        true = sandbox / false = production
     * @param timeoutSeconds HTTP timeout in seconds
     * @param retry          Retry configuration — null means use defaults
     *                       (retry disabled by default)
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

    // ── ConnectIPS ────────────────────────────────────────────────────────────

    /**
     * ConnectIPS payment gateway configuration.
     *
     * <p>Retry applies to: {@code validateTransaction()}.
     *
     * <p>Requires merchant registration with NCHL before use.
     * Contact connectips@nchl.com.np to register.
     *
     * <p>The {@code pfxPath} supports Spring Resource path prefixes:
     * <ul>
     *   <li>{@code file:/app/CREDITOR.pfx} — absolute file path</li>
     *   <li>{@code classpath:CREDITOR.pfx} — from classpath</li>
     * </ul>
     *
     * @param merchantId  NCHL-assigned merchant ID (integer)
     * @param appId       Application ID from NCHL
     * @param appName     Application name from NCHL
     * @param appPassword Application password for HTTP Basic Auth
     * @param pfxPath     Path to CREDITOR.pfx file (Spring Resource format)
     * @param pfxPassword Password for the CREDITOR.pfx file
     * @param sandbox     true = UAT (uat.connectips.com) / false = production
     * @param retry       Retry configuration — null means use defaults
     *                    (retry disabled by default)
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

    // ── Fonepay ───────────────────────────────────────────────────────────────

    /**
     * Fonepay payment gateway configuration.
     *
     * <p><strong>No retry configuration.</strong>
     * Fonepay uses URL redirect — it makes zero server-to-server
     * HTTP calls. Retry does not apply.
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

    /**
     * Metrics configuration — controls NepalPay Micrometer integration.
     *
     * <p>Metrics are opt-out — enabled by default when Actuator is on
     * the classpath. Set {@code nepalpay.metrics.enabled=false} to disable
     * all NepalPay Micrometer timers and counters.
     *
     * <p>YAML:
     * <pre>
     * nepalpay:
     *   metrics:
     *     enabled: false   # disable all NepalPay metrics
     * </pre>
     *
     * @param enabled true (default) = record metrics when Actuator present
     */
    public record MetricsProperties(
            @DefaultValue("true") boolean enabled
    ) {}
}