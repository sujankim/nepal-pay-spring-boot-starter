package io.nepalpay.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.util.PfxLoader;
import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.fonepay.FonepayClient;
import io.nepalpay.khalti.KhaltiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/**
 * NepalPay Metrics Auto-Configuration — Spring Boot 4.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration(before = NepalPayAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
        prefix         = "nepalpay.metrics",
        name           = "enabled",
        havingValue    = "true",
        matchIfMissing = true)
public class NepalPayMetricsAutoConfiguration {

    // ── Khalti ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(KhaltiClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.khalti", name = "secret-key")
    @ConditionalOnBean(MeterRegistry.class)
    public KhaltiClient khaltiClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry) {

        log.info("[NepalPay Metrics] Auto-configuring KhaltiClient" +
                        " with Micrometer metrics | sandbox={} | retry={}",
                properties.khalti().sandbox(),
                properties.khalti().retryOrDefault().summary());

        return new KhaltiClient(
                properties.khalti(),
                restClientBuilder,
                meterRegistry);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(EsewaClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.esewa", name = "secret-key")
    @ConditionalOnBean(MeterRegistry.class)
    public EsewaClient esewaClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry) {

        log.info("[NepalPay Metrics] Auto-configuring EsewaClient" +
                        " with Micrometer metrics | sandbox={} | retry={}",
                properties.esewa().sandbox(),
                properties.esewa().retryOrDefault().summary());

        String statusBaseUrl = properties.esewa().sandbox()
                ? "https://rc.esewa.com.np"
                : "https://esewa.com.np";

        return new EsewaClient(
                properties.esewa(),
                restClientBuilder,
                statusBaseUrl,
                meterRegistry);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ConnectIpsClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    @ConditionalOnBean(MeterRegistry.class)
    public ConnectIpsClient connectIpsClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props = properties.connectips();
        byte[] pfxBytes = loadPfxBytes(props.pfxPath(), resourceLoader);

        log.info("[NepalPay Metrics] Auto-configuring ConnectIpsClient" +
                        " with Micrometer metrics | mode={} | merchantId={}" +
                        " | timeout={}s | retry={}",
                props.sandbox() ? "UAT" : "PRODUCTION",
                props.merchantId(),
                props.timeoutSeconds(),
                props.retryOrDefault().summary());

        return new ConnectIpsClient(
                props.merchantId(),
                props.appId(),
                props.appName(),
                props.appPassword(),
                pfxBytes,
                props.pfxPassword(),
                props.sandbox(),
                restClientBuilder,
                props.retryOrDefault(),
                meterRegistry,
                props.timeoutSeconds()
        );
    }

    // ── Fonepay ───────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(FonepayClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.fonepay", name = "secret-key")
    @ConditionalOnBean(MeterRegistry.class)
    public FonepayClient fonepayClient(
            NepalPayProperties properties,
            MeterRegistry meterRegistry) {

        log.info("[NepalPay Metrics] Auto-configuring FonepayClient" +
                        " with Micrometer metrics | mode={} | merchantCode={}",
                properties.fonepay().sandbox() ? "SANDBOX" : "PRODUCTION",
                properties.fonepay().merchantCode());

        String gatewayUrl = properties.fonepay().sandbox()
                ? "https://dev.fonepay.com/api/merchantRequest"
                : "https://fonepay.com/api/merchantRequest";

        return new FonepayClient(
                properties.fonepay(),
                gatewayUrl,
                meterRegistry);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private byte[] loadPfxBytes(String pfxPath, ResourceLoader resourceLoader) {
        // Step 1 — validatePath is Spring-free, lives in nepal-pay-core
        PfxLoader.validatePath(pfxPath);

        Resource resource = resourceLoader.getResource(pfxPath);

        if (!resource.exists()) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx file not found at path: " + pfxPath +
                            ". Ensure the file exists at this location.");
        }

        try {
            // Step 2 — PfxLoader.read() owns and closes the stream (Bug #13 fix)
            return PfxLoader.read(resource.getInputStream(), pfxPath);
        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS .pfx file from path: "
                            + pfxPath + ". Error: " + e.getMessage(), e);
        }
    }
}