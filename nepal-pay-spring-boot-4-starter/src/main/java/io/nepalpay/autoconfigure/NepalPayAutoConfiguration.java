package io.nepalpay.autoconfigure;

import io.nepalpay.config.NepalPayProperties;
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
 * NepalPay Spring Boot Auto-Configuration — Spring Boot 4 variant.
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
 * <p>Retry is disabled by default for all clients.
 * Enable per gateway via {@code nepalpay.khalti.retry.enabled=true} etc.
 *
 * <p><strong>Boot 4 note:</strong>
 * EsewaClient uses {@code tools.jackson.databind.json.JsonMapper} (Jackson 3)
 * instead of Boot 3's {@code com.fasterxml.jackson.databind.ObjectMapper}.
 * All other behavior is identical to the Boot 3 auto-configuration.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NepalPayProperties.class)
public class NepalPayAutoConfiguration {

    // ── Khalti ───────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link KhaltiClient} when
     * {@code nepalpay.khalti.secret-key} is present.
     *
     * <p>Retry config is read from {@code nepalpay.khalti.retry.*}.
     * Defaults to disabled if not configured.
     *
     * @param properties        bound from nepalpay.* in application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     * @return configured KhaltiClient bean
     */
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

    /**
     * Auto-configures {@link EsewaClient} when
     * {@code nepalpay.esewa.secret-key} is present.
     *
     * <p>Retry config is read from {@code nepalpay.esewa.retry.*}.
     * Defaults to disabled if not configured.
     *
     * <p>Note: EsewaClient creates its own {@code JsonMapper} (Boot 4)
     * locally — no JsonMapper injection required here.
     *
     * @param properties        bound from nepalpay.* in application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     * @return configured EsewaClient bean
     */
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

    /**
     * Auto-configures {@link ConnectIpsClient} when
     * {@code nepalpay.connectips.merchant-id} is present.
     *
     * <p>This bean reads the CREDITOR.pfx file from the path configured
     * in {@code nepalpay.connectips.pfx-path} using Spring's
     * {@link ResourceLoader}. Supported path formats:
     * <ul>
     *   <li>{@code file:/app/CREDITOR.pfx} — absolute file path</li>
     *   <li>{@code classpath:CREDITOR.pfx} — classpath resource</li>
     * </ul>
     *
     * <p>Throws {@link ConnectIpsException} at startup if the .pfx file
     * cannot be found or read — this is intentional so misconfiguration
     * is detected immediately rather than at first payment attempt.
     *
     * <p>Retry config is read from {@code nepalpay.connectips.retry.*}.
     * Defaults to disabled if not configured.
     *
     * @param properties        bound from nepalpay.* in application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     * @param resourceLoader    Spring ResourceLoader for reading .pfx file
     * @return configured ConnectIpsClient bean
     * @throws ConnectIpsException if .pfx file cannot be loaded
     */
    @Bean
    @ConditionalOnMissingBean(ConnectIpsClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    public ConnectIpsClient connectIpsClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props = properties.connectips();

        byte[] pfxBytes = loadPfxBytes(props.pfxPath(), resourceLoader);

        log.info("[NepalPay] Auto-configuring ConnectIpsClient | mode={} " +
                        "| merchantId={} | retry={}",
                props.sandbox() ? "UAT" : "PRODUCTION",
                props.merchantId(),
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
                props.retryOrDefault()
        );
    }

    // ── Fonepay ───────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link FonepayClient} when
     * {@code nepalpay.fonepay.secret-key} is present.
     *
     * <p>Note: FonepayClient does NOT need RestClient.Builder —
     * Fonepay uses URL redirect, not server-to-server API calls.
     * Retry does not apply to Fonepay.
     *
     * @param properties bound from nepalpay.* in application.yml
     * @return configured FonepayClient bean
     */
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

    /**
     * Load .pfx file bytes from a Spring Resource path.
     *
     * <p>Fails fast at application startup with a clear error message
     * if the file cannot be found or read.
     *
     * @param pfxPath        path to the .pfx file (Spring Resource format)
     * @param resourceLoader Spring ResourceLoader
     * @return file contents as byte array
     * @throws ConnectIpsException if file cannot be loaded
     */
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

            byte[] bytes = resource.getInputStream().readAllBytes();

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