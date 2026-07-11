package io.nepalpay.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.nepalpay.config.NepalPayProperties;
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
 * NepalPay Metrics Auto-Configuration — Spring Boot 3.
 *
 * <p>Activates only when <strong>both</strong> conditions are true:
 * <ol>
 *   <li>{@code spring-boot-starter-actuator} is on the classpath
 *       ({@code @ConditionalOnClass(MeterRegistry.class)})</li>
 *   <li>{@code nepalpay.metrics.enabled} is {@code true}
 *       (defaults to {@code true} — opt-out behavior)</li>
 * </ol>
 *
 * <p>When active, this class creates metrics-enabled gateway client
 * beans BEFORE {@link NepalPayAutoConfiguration} runs. The plain
 * auto-configuration then sees each bean already registered via
 * {@code @ConditionalOnMissingBean} and skips its own definitions.
 *
 * <p><strong>Disable metrics entirely:</strong>
 * <pre>
 * nepalpay:
 *   metrics:
 *     enabled: false
 * </pre>
 *
 * @author Sujan Lamichhane
 * @see NepalPayAutoConfiguration
 * @see NepalPayHealthAutoConfiguration
 */
@Slf4j
@AutoConfiguration(before = NepalPayAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
        prefix         = "nepalpay.metrics",
        name           = "enabled",
        havingValue    = "true",
        matchIfMissing = true)   // opt-out — active by default
public class NepalPayMetricsAutoConfiguration {

    // ── Khalti ────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link KhaltiClient} WITH Micrometer metrics.
     *
     * <p>Runs before {@link NepalPayAutoConfiguration#khaltiClient} via
     * {@code @AutoConfiguration(before = ...)}. When this bean is created,
     * the plain auto-configuration sees {@code KhaltiClient} already
     * registered and skips its own bean definition.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.khalti.secret-key} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code KhaltiClient} bean (developer override wins)</li>
     * </ul>
     *
     * @param properties        bound NepalPay configuration
     * @param restClientBuilder Spring Boot RestClient builder
     * @param meterRegistry     Micrometer registry from Actuator
     * @return metrics-enabled KhaltiClient
     */
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

        // KhaltiClient(props, builder, MeterRegistry) constructor
        // is unambiguous — no String overload exists at same arity
        return new KhaltiClient(
                properties.khalti(),
                restClientBuilder,
                meterRegistry);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link EsewaClient} WITH Micrometer metrics.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.esewa.secret-key} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code EsewaClient} bean</li>
     * </ul>
     *
     * <p><strong>Constructor note:</strong>
     * Uses the 4-arg constructor
     * {@code EsewaClient(props, builder, statusBaseUrl, meterRegistry)}
     * with an explicit {@code statusBaseUrl}. The 3-arg
     * {@code EsewaClient(props, builder, MeterRegistry)} overload was
     * removed because it was ambiguous with the String test constructor
     * when {@code null} was passed.
     *
     * @param properties        bound NepalPay configuration
     * @param restClientBuilder Spring Boot RestClient builder
     * @param meterRegistry     Micrometer registry from Actuator
     * @return metrics-enabled EsewaClient
     */
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
                statusBaseUrl,          // ← explicit URL — no ambiguity
                meterRegistry);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link ConnectIpsClient} WITH Micrometer metrics.
     *
     * <p>Reads the CREDITOR.pfx file from the path configured in
     * {@code nepalpay.connectips.pfx-path} using Spring's
     * {@link ResourceLoader}. Fails fast at startup if the file is
     * missing or invalid.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.connectips.merchant-id} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code ConnectIpsClient} bean</li>
     * </ul>
     *
     * @param properties        bound NepalPay configuration
     * @param restClientBuilder Spring Boot RestClient builder
     * @param meterRegistry     Micrometer registry from Actuator
     * @param resourceLoader    Spring ResourceLoader for reading .pfx file
     * @return metrics-enabled ConnectIpsClient
     * @throws ConnectIpsException if .pfx file cannot be loaded
     */
    @Bean
    @ConditionalOnMissingBean(ConnectIpsClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    @ConditionalOnBean(MeterRegistry.class)
    public ConnectIpsClient connectIpsClient(
            NepalPayProperties properties,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props =
                properties.connectips();

        byte[] pfxBytes = loadPfxBytes(props.pfxPath(), resourceLoader);

        log.info("[NepalPay Metrics] Auto-configuring ConnectIpsClient" +
                        " with Micrometer metrics | mode={} | merchantId={}" +
                        " | retry={}",
                props.sandbox() ? "UAT" : "PRODUCTION",
                props.merchantId(),
                props.retryOrDefault().summary());

        // ConnectIpsClient 10-arg + MeterRegistry constructor
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
                meterRegistry);
    }

    // ── Fonepay ───────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link FonepayClient} WITH Micrometer metrics.
     *
     * <p>Note: FonepayClient does NOT need {@code RestClient.Builder} —
     * Fonepay uses URL redirect and makes no server-to-server HTTP calls.
     * Only event counters are recorded (no latency timers).
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.fonepay.secret-key} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code FonepayClient} bean</li>
     * </ul>
     *
     * <p><strong>Constructor note:</strong>
     * Uses the 3-arg constructor
     * {@code FonepayClient(props, gatewayUrl, meterRegistry)}
     * with an explicit {@code gatewayUrl}. The 2-arg
     * {@code FonepayClient(props, MeterRegistry)} overload was
     * removed because it was ambiguous with the String test constructor
     * when {@code null} was passed.
     *
     * @param properties    bound NepalPay configuration
     * @param meterRegistry Micrometer registry from Actuator
     * @return metrics-enabled FonepayClient
     */
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

        // ✅ FIX: use 3-arg constructor (props, gatewayUrl, registry)
        // The 2-arg (props, MeterRegistry) was removed — ambiguous
        // with (props, String) test constructor when null was passed.
        String gatewayUrl = properties.fonepay().sandbox()
                ? "https://dev.fonepay.com/api/merchantRequest"
                : "https://fonepay.com/api/merchantRequest";

        return new FonepayClient(
                properties.fonepay(),
                gatewayUrl,             // ← explicit URL — no ambiguity
                meterRegistry);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Load .pfx file bytes from a Spring Resource path.
     *
     * <p>Fails fast at startup with a clear error if the file is missing.
     * Uses try-with-resources to prevent InputStream file descriptor leaks
     * (Bug #13 fix).
     *
     * @param pfxPath        path to the .pfx file (Spring Resource format)
     * @param resourceLoader Spring ResourceLoader
     * @return file contents as byte array
     * @throws ConnectIpsException if file cannot be loaded
     */
    private byte[] loadPfxBytes(
            String pfxPath, ResourceLoader resourceLoader) {

        if (pfxPath == null || pfxPath.isBlank()) {
            throw new ConnectIpsException(
                    "ConnectIPS pfx-path is not configured. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Example: file:/app/CREDITOR.pfx or " +
                            "classpath:CREDITOR.pfx. " +
                            "Contact NCHL at connectips@nchl.com.np.");
        }

        try {
            Resource resource = resourceLoader.getResource(pfxPath);

            if (!resource.exists()) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file not found at path: " + pfxPath +
                                ". Ensure the file exists at this location.");
            }

            //  try-with-resources — prevents InputStream file descriptor leak
            final byte[] bytes;
            try (var inputStream = resource.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }

            if (bytes.length == 0) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file is empty at path: " + pfxPath);
            }

            log.info("[NepalPay Metrics] ConnectIPS .pfx loaded" +
                    " | path={} | bytes={}", pfxPath, bytes.length);

            return bytes;

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS .pfx file from path: "
                            + pfxPath + ". Error: " + e.getMessage(), e);
        }
    }
}