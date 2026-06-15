package io.nepalpay.config;

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
 * </pre>
 *
 * @param khalti    Khalti payment gateway configuration
 * @param esewa     eSewa payment gateway configuration
 * @param connectips ConnectIPS payment gateway configuration
 * @param fonepay   Fonepay payment gateway configuration
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties     khalti,
        EsewaProperties      esewa,
        ConnectIpsProperties connectips,
        FonepayProperties    fonepay
) {

    // ── Khalti ───────────────────────────────────────────────────────────────

    /**
     * Khalti payment gateway configuration.
     *
     * @param secretKey      Your Khalti secret key from merchant dashboard
     * @param returnUrl      URL Khalti redirects to after payment
     * @param websiteUrl     Your merchant website URL
     * @param sandbox        true = dev.khalti.com / false = khalti.com (production)
     * @param timeoutSeconds HTTP timeout in seconds for Khalti API calls
     */
    public record KhaltiProperties(
            String secretKey,
            String returnUrl,
            String websiteUrl,
            @DefaultValue("true") boolean sandbox,
            @DefaultValue("10") int timeoutSeconds
    ) {}

    // ── eSewa ─────────────────────────────────────────────────────────────────

    /**
     * eSewa payment gateway configuration.
     *
     * <p>Sandbox credentials:
     * Secret key: {@code 8gBm/:&amp;EnhH.1/q} | Product code: {@code EPAYTEST}
     *
     * <p>Warning: {@code productCode} is the most common cause of
     * sandbox-to-production breakage. Always set via environment variable.
     *
     * @param secretKey      eSewa HMAC-SHA256 secret key
     * @param productCode    Sandbox: EPAYTEST / Production: your merchant code
     * @param successUrl     URL eSewa redirects to on successful payment
     * @param failureUrl     URL eSewa redirects to on failed payment
     * @param sandbox        true = sandbox / false = production
     * @param timeoutSeconds HTTP timeout in seconds
     */
    public record EsewaProperties(
            String secretKey,
            String productCode,
            String successUrl,
            String failureUrl,
            @DefaultValue("true") boolean sandbox,
            @DefaultValue("10") int timeoutSeconds
    ) {}

    // ── ConnectIPS ────────────────────────────────────────────────────────────

    /**
     * ConnectIPS payment gateway configuration.
     *
     * <p>Requires merchant registration with NCHL before use.
     * Contact connectips@nchl.com.np to register.
     *
     * <p>The {@code pfxPath} supports Spring Resource path prefixes:
     * <ul>
     *   <li>{@code file:/app/CREDITOR.pfx} — absolute file path</li>
     *   <li>{@code classpath:CREDITOR.pfx} — from classpath (not recommended for production)</li>
     * </ul>
     *
     * <p>Warning: Never commit your CREDITOR.pfx file to Git.
     * Add {@code *.pfx} to your {@code .gitignore}.
     *
     * @param merchantId  NCHL-assigned merchant ID (integer)
     * @param appId       Application ID from NCHL
     * @param appName     Application name from NCHL
     * @param appPassword Application password for HTTP Basic Auth on validate API
     * @param pfxPath     Path to CREDITOR.pfx file (Spring Resource format)
     * @param pfxPassword Password for the CREDITOR.pfx file
     * @param sandbox     true = UAT (uat.connectips.com) / false = production
     */
    public record ConnectIpsProperties(
            int merchantId,
            String appId,
            String appName,
            String appPassword,
            String pfxPath,
            String pfxPassword,
            @DefaultValue("true") boolean sandbox
    ) {}

    // ── Fonepay ───────────────────────────────────────────────────────────────

    /**
     * Fonepay payment gateway configuration.
     *
     * @param merchantCode Your Fonepay merchant code (PID)
     * @param secretKey    Your Fonepay HMAC-SHA512 secret key
     * @param returnUrl    URL Fonepay redirects to after payment
     * @param sandbox      true = dev.fonepay.com / false = fonepay.com (production)
     */
    public record FonepayProperties(
            String merchantCode,
            String secretKey,
            String returnUrl,
            @DefaultValue("true") boolean sandbox
    ) {}
}