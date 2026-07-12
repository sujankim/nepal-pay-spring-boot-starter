package io.nepalpay.reactive.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.reactive.config.NepalPayProperties;
import io.nepalpay.core.util.PfxLoader;
import io.nepalpay.reactive.connectips.ConnectIpsReactiveClient;
import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * NepalPay Reactive Metrics Auto-Configuration.
 *
 * <p>Activates only when <strong>both</strong> conditions are true:
 * <ol>
 *   <li>{@code spring-boot-starter-actuator} is on the classpath
 *       ({@code @ConditionalOnClass(MeterRegistry.class)})</li>
 *   <li>{@code nepalpay.metrics.enabled} is {@code true} (default)</li>
 * </ol>
 *
 * <p>When active, creates metrics-enabled reactive client beans BEFORE
 * {@link NepalPayReactiveAutoConfiguration} runs. The plain auto-config
 * sees each bean already registered via {@code @ConditionalOnMissingBean}
 * and skips its own definitions.
 *
 * <p>Disable metrics entirely:
 * <pre>
 * nepalpay:
 *   metrics:
 *     enabled: false
 * </pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration(before = NepalPayReactiveAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
        prefix         = "nepalpay.metrics",
        name           = "enabled",
        havingValue    = "true",
        matchIfMissing = true)
public class NepalPayReactiveMetricsAutoConfiguration {

    // ── Khalti ────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link KhaltiReactiveClient} WITH Micrometer metrics.
     *
     * <p>Uses the 3-arg metrics constructor:
     * {@code KhaltiReactiveClient(props, builder, meterRegistry)}.
     * This is unambiguous — no String overload exists at 3-arg arity.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.khalti.secret-key} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code KhaltiReactiveClient} bean</li>
     * </ul>
     *
     * @param properties       bound NepalPay configuration
     * @param webClientBuilder Spring WebClient builder
     * @param meterRegistry    Micrometer registry from Actuator
     * @return metrics-enabled KhaltiReactiveClient
     */
    @Bean
    @ConditionalOnMissingBean(KhaltiReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.khalti", name = "secret-key")
    @ConditionalOnBean(MeterRegistry.class)
    public KhaltiReactiveClient khaltiReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {

        log.info("[NepalPay Reactive Metrics] Auto-configuring" +
                        " KhaltiReactiveClient with Micrometer metrics" +
                        " | sandbox={} | retry={}",
                properties.khalti().sandbox(),
                properties.khalti().retryOrDefault().summary());

        return new KhaltiReactiveClient(
                properties.khalti(),
                webClientBuilder,
                meterRegistry);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link EsewaReactiveClient} WITH Micrometer metrics.
     *
     * <p>Uses the 4-arg constructor:
     * {@code EsewaReactiveClient(props, builder, statusBaseUrl, meterRegistry)}
     * with an explicit {@code statusBaseUrl} derived from the sandbox flag.
     * The 3-arg {@code (props, builder, MeterRegistry)} overload was NOT
     * added to avoid ambiguity with the String test constructor.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.esewa.secret-key} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code EsewaReactiveClient} bean</li>
     * </ul>
     *
     * @param properties       bound NepalPay configuration
     * @param webClientBuilder Spring WebClient builder
     * @param meterRegistry    Micrometer registry from Actuator
     * @return metrics-enabled EsewaReactiveClient
     */
    @Bean
    @ConditionalOnMissingBean(EsewaReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.esewa", name = "secret-key")
    @ConditionalOnBean(MeterRegistry.class)
    public EsewaReactiveClient esewaReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {

        log.info("[NepalPay Reactive Metrics] Auto-configuring" +
                        " EsewaReactiveClient with Micrometer metrics" +
                        " | sandbox={} | retry={}",
                properties.esewa().sandbox(),
                properties.esewa().retryOrDefault().summary());

        // Explicit statusBaseUrl — avoids ambiguity with String test constructor
        String statusBaseUrl = properties.esewa().sandbox()
                ? "https://rc.esewa.com.np"
                : "https://esewa.com.np";

        return new EsewaReactiveClient(
                properties.esewa(),
                webClientBuilder,
                statusBaseUrl,
                meterRegistry);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link ConnectIpsReactiveClient} WITH Micrometer metrics.
     *
     * <p>Uses the public constructor:
     * {@code ConnectIpsReactiveClient(... RetryProperties, MeterRegistry)}
     * which delegates to the private core constructor.
     *
     * <p>Only created when:
     * <ul>
     *   <li>{@code nepalpay.connectips.merchant-id} is present</li>
     *   <li>A {@link MeterRegistry} bean exists in context</li>
     *   <li>No existing {@code ConnectIpsReactiveClient} bean</li>
     * </ul>
     *
     * @param properties       bound NepalPay configuration
     * @param webClientBuilder Spring WebClient builder
     * @param meterRegistry    Micrometer registry from Actuator
     * @param resourceLoader   Spring ResourceLoader for .pfx file
     * @return metrics-enabled ConnectIpsReactiveClient
     * @throws ConnectIpsException if .pfx file cannot be loaded
     */
    @Bean
    @ConditionalOnMissingBean(ConnectIpsReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    @ConditionalOnBean(MeterRegistry.class)
    public ConnectIpsReactiveClient connectIpsReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props =
                properties.connectips();
        byte[] pfxBytes = loadPfxBytes(props.pfxPath(), resourceLoader);

        log.info("[NepalPay Reactive Metrics] Auto-configuring" +
                        " ConnectIpsReactiveClient with Micrometer metrics" +
                        " | mode={} | merchantId={} | retry={}",
                props.sandbox() ? "UAT" : "PRODUCTION",
                props.merchantId(),
                props.retryOrDefault().summary());

        // Uses public constructor: (... RetryProperties, MeterRegistry)
        return new ConnectIpsReactiveClient(
                props.merchantId(),
                props.appId(),
                props.appName(),
                props.appPassword(),
                pfxBytes,
                props.pfxPassword(),
                props.sandbox(),
                webClientBuilder,
                props.retryOrDefault(),
                meterRegistry,
                props.timeoutSeconds()
        );
    }

    // ── Fonepay — no reactive metrics ─────────────────────────────────────
    // FonepayClient is blocking and makes no HTTP calls.
    // Use FonepayClient from blocking starter in reactive apps.
    // Metrics wiring for Fonepay is handled by the blocking starter's
    // NepalPayMetricsAutoConfiguration.

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Load .pfx file bytes from a Spring Resource path.
     * Delegates validation and stream reading to PfxLoader in nepal-pay-core.
     *
     * @param pfxPath        path to .pfx file (Spring Resource format)
     * @param resourceLoader Spring ResourceLoader
     * @return file contents as byte array
     * @throws ConnectIpsException if file cannot be loaded
     */
    private byte[] loadPfxBytes(
            String pfxPath, ResourceLoader resourceLoader) {

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