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
 *   esewa:
 *     secret-key:   ${ESEWA_SECRET_KEY}
 *     product-code: ${ESEWA_PRODUCT_CODE}
 *     success-url:  ${ESEWA_SUCCESS_URL}
 *     failure-url:  ${ESEWA_FAILURE_URL}
 *     sandbox: true
 * </pre>
 *
 * @param khalti Khalti payment gateway configuration
 * @param esewa  eSewa payment gateway configuration
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties khalti,
        EsewaProperties esewa
) {

    /**
     * Khalti payment gateway configuration.
     *
     * @param secretKey      Your Khalti secret key from merchant dashboard
     * @param returnUrl      URL Khalti redirects to after payment
     * @param websiteUrl     Your merchant website URL
     * @param sandbox        true uses sandbox, false uses production
     * @param timeoutSeconds HTTP timeout in seconds for Khalti API calls
     */
    public record KhaltiProperties(
            String secretKey,
            String returnUrl,
            String websiteUrl,
            @DefaultValue("true") boolean sandbox,
            @DefaultValue("10") int timeoutSeconds
    ) {}

    /**
     * eSewa payment gateway configuration.
     *
     * <p>The {@code secretKey} is your HMAC secret from the eSewa merchant portal.
     * Sandbox value: {@code 8gBm/:and EnhH.1/q} (use the value from official docs).
     *
     * <p><strong>WARNING:</strong> {@code productCode} is the most common cause of
     * sandbox-to-production breakage. Sandbox uses {@code EPAYTEST}.
     * Production uses your real merchant code. Always set via environment variable.
     *
     * @param secretKey      eSewa HMAC secret key
     * @param productCode    eSewa merchant code (Sandbox: EPAYTEST)
     * @param successUrl     URL eSewa redirects to on successful payment
     * @param failureUrl     URL eSewa redirects to on failed payment
     * @param sandbox        true uses sandbox, false uses production
     * @param timeoutSeconds HTTP timeout in seconds for eSewa API calls
     */
    public record EsewaProperties(
            String secretKey,
            String productCode,
            String successUrl,
            String failureUrl,
            @DefaultValue("true") boolean sandbox,
            @DefaultValue("10") int timeoutSeconds
    ) {}
}