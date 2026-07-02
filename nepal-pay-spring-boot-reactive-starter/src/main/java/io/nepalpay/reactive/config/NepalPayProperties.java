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
 * </pre>
 */
@ConfigurationProperties(prefix = "nepalpay")
public record NepalPayProperties(
        KhaltiProperties     khalti,
        EsewaProperties      esewa,
        ConnectIpsProperties connectips,
        FonepayProperties    fonepay
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
}