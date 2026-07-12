package io.nepalpay.reactive.autoconfigure;

import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.reactive.config.NepalPayProperties;
import io.nepalpay.core.util.PfxLoader;
import io.nepalpay.reactive.connectips.ConnectIpsReactiveClient;
import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * NepalPay Reactive Auto-Configuration.
 *
 * <p>Spring Boot loads this class automatically from:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *
 * <p>Beans created (each only when their key/merchant ID is configured):
 * <ul>
 *   <li>{@link KhaltiReactiveClient}     — when khalti.secret-key present</li>
 *   <li>{@link EsewaReactiveClient}      — when esewa.secret-key present</li>
 *   <li>{@link ConnectIpsReactiveClient} — when connectips.merchant-id present</li>
 * </ul>
 *
 * <p>Fonepay: use {@code FonepayClient} from the blocking starter —
 * it makes no HTTP calls and works in reactive apps without any changes.
 *
 * <p>{@code @ConditionalOnClass(WebClient.class)} ensures this
 * auto-configuration only activates when WebFlux is on the classpath.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(NepalPayProperties.class)
public class NepalPayReactiveAutoConfiguration {

    // ── Khalti ───────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link KhaltiReactiveClient}.
     */
    @Bean
    @ConditionalOnMissingBean(KhaltiReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.khalti", name = "secret-key")
    public KhaltiReactiveClient khaltiReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder) {

        log.info("[NepalPay Reactive] Auto-configuring KhaltiReactiveClient" +
                        " | sandbox={} | retry={}",
                properties.khalti().sandbox(),
                properties.khalti().retryOrDefault().summary());

        return new KhaltiReactiveClient(
                properties.khalti(), webClientBuilder);
    }

    // ── eSewa ─────────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link EsewaReactiveClient}.
     */
    @Bean
    @ConditionalOnMissingBean(EsewaReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.esewa", name = "secret-key")
    public EsewaReactiveClient esewaReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder) {

        log.info("[NepalPay Reactive] Auto-configuring EsewaReactiveClient" +
                        " | sandbox={} | retry={}",
                properties.esewa().sandbox(),
                properties.esewa().retryOrDefault().summary());

        return new EsewaReactiveClient(
                properties.esewa(), webClientBuilder);
    }

    // ── ConnectIPS ────────────────────────────────────────────────────────

    /**
     * Auto-configures {@link ConnectIpsReactiveClient}.
     */
    @Bean
    @ConditionalOnMissingBean(ConnectIpsReactiveClient.class)
    @ConditionalOnProperty(prefix = "nepalpay.connectips", name = "merchant-id")
    public ConnectIpsReactiveClient connectIpsReactiveClient(
            NepalPayProperties properties,
            WebClient.Builder webClientBuilder,
            ResourceLoader resourceLoader) {

        NepalPayProperties.ConnectIpsProperties props = properties.connectips();
        byte[] pfxBytes = loadPfxBytes(props.pfxPath(), resourceLoader);

        log.info("[NepalPay Reactive] Auto-configuring ConnectIpsReactiveClient" +
                        " | mode={} | merchantId={} | retry={}",
                props.sandbox() ? "UAT" : "PRODUCTION",
                props.merchantId(),
                props.retryOrDefault().summary());

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
                null,
                props.timeoutSeconds()
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Load .pfx file bytes from a Spring Resource path.
     *
     * <p>Fails fast at application startup with a clear error message
     * if the file cannot be found or read.
     *
     * <p><strong></strong> Uses try-with-resources to
     * guarantee the {@link java.io.InputStream} is closed after reading,
     * even if an exception is thrown during {@code readAllBytes()}.
     * The previous implementation called {@code resource.getInputStream()}
     * without closing it — file descriptors accumulated on each application
     * restart, eventually exhausting the OS file descriptor limit.
     *
     * @param pfxPath        path to the .pfx file (Spring Resource format)
     * @param resourceLoader Spring ResourceLoader
     * @return file contents as byte array
     * @throws ConnectIpsException if file cannot be loaded
     */
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