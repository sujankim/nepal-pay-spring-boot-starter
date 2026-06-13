package io.nepalpay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Root configuration properties for NepalPay Spring Boot Starter.
 *
 * Add to your application.yml:
 * <pre>
 * nepalpay:
 *   khalti:
 *     secret-key: ${KHALTI_SECRET_KEY}
 *     return-url: ${KHALTI_RETURN_URL}
 *     website-url: ${YOUR_WEBSITE_URL}
 *     sandbox: true
 *   esewa:
 *     secret-key: ${ESEWA_SECRET_KEY}
 *     product-code: ${ESEWA_PRODUCT_CODE}
 *     success-url: ${ESEWA_SUCCESS_URL}
 *     failure-url: ${ESEWA_FAILURE_URL}
 *     sandbox: true
 * </pre>
 *
 * @author Sujan Lamichhane
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties khalti,
        EsewaProperties esewa
) {

    /**
     * Khalti payment gateway configuration.
     *
     * @param secretKey   Your Khalti secret key (live_secret_key_xxx or test_secret_key_xxx)
     * @param returnUrl   URL Khalti redirects to after payment
     * @param websiteUrl  Your merchant website URL
     * @param sandbox     true = sandbox (dev.khalti.com), false = production (khalti.com)
     * @param timeoutSeconds HTTP timeout for Khalti API calls (default 10s)
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
     * @param secretKey   eSewa HMAC secret key
     *                    Sandbox: 8gBm/:&EnhH.1/q | Production: from eSewa merchant portal
     * @param productCode eSewa merchant/product code
     *                    Sandbox: EPAYTEST | Production: your merchant code
     *                    ⚠️ Most common cause of sandbox→production breakage!
     * @param successUrl  URL eSewa redirects to on success
     * @param failureUrl  URL eSewa redirects to on failure
     * @param sandbox     true = sandbox, false = production
     * @param timeoutSeconds HTTP timeout for eSewa verify API calls (default 10s)
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