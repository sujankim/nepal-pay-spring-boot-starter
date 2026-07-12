package io.nepalpay.autoconfigure;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.util.PfxLoader;
import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.fonepay.FonepayClient;
import io.nepalpay.khalti.KhaltiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/**
 * NepalPay Spring Boot Auto-Configuration — Spring Boot 3 variant.
 *
 * <p>Spring Boot loads this class automatically from:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *
 * <p>Beans created (each only when their secret key / merchant ID is configured):
 * <ul>
 *   <li>{@link KhaltiClient}     — when {@code nepalpay.khalti.secret-key} is present</li>
 *   <li>{@link EsewaClient}      — when {@code nepalpay.esewa.secret-key} is present</li>
 *   <li>{@link ConnectIpsClient} — when {@code nepalpay.connectips.merchant-id} is present</li>
 *   <li>{@link FonepayClient}    — when {@code nepalpay.fonepay.secret-key} is present</li>
 * </ul>
 *
 * <p>Every bean uses {@code @ConditionalOnMissingBean} — define your own bean
 * and NepalPay steps aside automatically.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NepalPayProperties.class)
public class NepalPayAutoConfiguration {

    // ── Khalti ───────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(KhaltiClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.khalti", name = "secret-key")
    public KhaltiClient khaltiClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder) {

        log.info("[NepalPay] Auto-configuring KhaltiClient | sandbox={} | retry={}",
                properties.khalti().sandbox(),
                properties.khalti().retryOrDefault().summary());

        return new KhaltiClient(properties.khalti(), restClientBuilder);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(EsewaClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.esewa", name = "secret-key")
    public EsewaClient esewaClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder) {

        log.info("[NepalPay] Auto-configuring EsewaClient | sandbox={} | retry={}",
                properties.esewa().sandbox(),
                properties.esewa().retryOrDefault().summary());

        return new EsewaClient(properties.esewa(), restClientBuilder);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ConnectIpsClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    public ConnectIpsClient connectIpsClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props = properties.connectips();
        byte[] pfxBytes = PfxLoader.load(props.pfxPath(), resourceLoader);

        log.info("[NepalPay] Auto-configuring ConnectIpsClient | mode={}" +
                        " | merchantId={} | timeout={}s | retry={}",
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
                null,
                props.timeoutSeconds()
        );
    }

    // ── Fonepay ───────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(FonepayClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.fonepay", name = "secret-key")
    public FonepayClient fonepayClient(NepalPayProperties properties) {

        log.info("[NepalPay] Auto-configuring FonepayClient | mode={} | merchantCode={}",
                properties.fonepay().sandbox() ? "SANDBOX" : "PRODUCTION",
                properties.fonepay().merchantCode());

        return new FonepayClient(properties.fonepay());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] loadPfxBytes(String pfxPath, ResourceLoader resourceLoader) {
        if (pfxPath == null || pfxPath.isBlank()) {
            throw new ConnectIpsException(
                    "ConnectIPS pfx-path is not configured. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Example: file:/app/CREDITOR.pfx or classpath:CREDITOR.pfx. " +
                            "Contact NCHL at connectips@nchl.com.np to obtain your certificate.");
        }

        try {
            Resource resource = resourceLoader.getResource(pfxPath);

            if (!resource.exists()) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file not found at path: " + pfxPath +
                                ". Ensure the file exists at this location on your server.");
            }

            final byte[] bytes;
            try (var inputStream = resource.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }

            if (bytes.length == 0) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file is empty at path: " + pfxPath);
            }

            log.info("[NepalPay] ConnectIPS .pfx loaded | path={} | bytes={}",
                    pfxPath, bytes.length);

            return bytes;

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS .pfx file from path: " + pfxPath +
                            ". Error: " + e.getMessage(), e);
        }
    }
}