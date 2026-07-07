package io.nepalpay.reactive.autoconfigure;

import io.nepalpay.reactive.connectips.ConnectIpsReactiveClient;
import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NepalPayReactiveAutoConfiguration}.
 *
 * <p>Uses {@link ApplicationContextRunner} — same approach as the
 * blocking starter tests. Fast, no real server, no network.
 *
 * <p>ConnectIPS URL tests live in {@code ConnectIpsReactiveClientTest}
 * — same package access rule as the blocking starter.
 */
@DisplayName("NepalPayReactiveAutoConfiguration")
class NepalPayReactiveAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            NepalPayReactiveAutoConfiguration.class))
                    .withBean(WebClient.Builder.class, WebClient::builder);

    // ── KhaltiReactiveClient ──────────────────────────────────────────────

    @Nested
    @DisplayName("KhaltiReactiveClient bean")
    class KhaltiReactiveClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.khalti.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb"
                    )
                    .run(context -> {
                        assertThat(context)
                                .hasSingleBean(KhaltiReactiveClient.class);
                        assertThat(context)
                                .doesNotHaveBean(EsewaReactiveClient.class);
                    });
        }

        @Test
        @DisplayName("is NOT created when secret-key is absent")
        void notCreatedWhenSecretKeyAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context)
                                    .doesNotHaveBean(
                                            KhaltiReactiveClient.class));
        }

        @Test
        @DisplayName("sandbox=true by default")
        void sandboxTrueByDefault() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb"
                    )
                    .run(context -> {
                        KhaltiReactiveClient bean =
                                context.getBean(KhaltiReactiveClient.class);
                        assertThat(bean.isSandbox()).isTrue();
                        assertThat(bean.baseUrl())
                                .isEqualTo("https://dev.khalti.com/api/v2");
                    });
        }

        @Test
        @DisplayName("sandbox=false gives production base URL")
        void sandboxFalse_productionUrl() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=live_key",
                            "nepalpay.khalti.return-url=https://example.com/cb",
                            "nepalpay.khalti.sandbox=false"
                    )
                    .run(context -> {
                        KhaltiReactiveClient bean =
                                context.getBean(KhaltiReactiveClient.class);
                        assertThat(bean.isSandbox()).isFalse();
                        assertThat(bean.baseUrl())
                                .isEqualTo("https://khalti.com/api/v2");
                    });
        }

        @Test
        @DisplayName("developer custom bean takes precedence")
        void developerBeanTakesPrecedence() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb"
                    )
                    .withBean(KhaltiReactiveClient.class, () ->
                            new KhaltiReactiveClient(
                                    new io.nepalpay.reactive.config
                                            .NepalPayProperties.KhaltiProperties(
                                            "custom", "https://custom.com",
                                            null, true, 10, null),
                                    WebClient.builder(),
                                    "https://custom.com"))
                    .run(context -> {
                        assertThat(context)
                                .hasSingleBean(KhaltiReactiveClient.class);
                        assertThat(context.getBean(KhaltiReactiveClient.class)
                                .baseUrl())
                                .isEqualTo("https://custom.com");
                    });
        }
    }

    // ── EsewaReactiveClient ───────────────────────────────────────────────

    @Nested
    @DisplayName("EsewaReactiveClient bean")
    class EsewaReactiveClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.esewa.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/s",
                            "nepalpay.esewa.failure-url=https://example.com/f"
                    )
                    .run(context ->
                            assertThat(context)
                                    .hasSingleBean(EsewaReactiveClient.class));
        }

        @Test
        @DisplayName("is NOT created when secret-key is absent")
        void notCreatedWhenSecretKeyAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context)
                                    .doesNotHaveBean(
                                            EsewaReactiveClient.class));
        }

        @Test
        @DisplayName("sandbox=true gives sandbox formActionUrl")
        void sandboxTrueByDefault() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/s",
                            "nepalpay.esewa.failure-url=https://example.com/f"
                    )
                    .run(context -> {
                        EsewaReactiveClient bean =
                                context.getBean(EsewaReactiveClient.class);
                        assertThat(bean.isSandbox()).isTrue();
                        assertThat(bean.formActionUrl())
                                .isEqualTo(
                                        "https://rc-epay.esewa.com.np/api/epay/main/v2/form");
                    });
        }

        @Test
        @DisplayName("sandbox=false gives production formActionUrl")
        void sandboxFalse_productionFormUrl() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=live_key",
                            "nepalpay.esewa.product-code=LIVE_CODE",
                            "nepalpay.esewa.success-url=https://example.com/s",
                            "nepalpay.esewa.failure-url=https://example.com/f",
                            "nepalpay.esewa.sandbox=false"
                    )
                    .run(context -> {
                        EsewaReactiveClient bean =
                                context.getBean(EsewaReactiveClient.class);
                        assertThat(bean.isSandbox()).isFalse();
                        assertThat(bean.formActionUrl())
                                .isEqualTo(
                                        "https://epay.esewa.com.np/api/epay/main/v2/form");
                    });
        }
    }

    // ── ConnectIpsReactiveClient ──────────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsReactiveClient bean")
    class ConnectIpsReactiveClientBeanTests {

        @Test
        @DisplayName("is NOT created when connectips properties are absent")
        void notCreatedWhenAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context)
                                    .doesNotHaveBean(
                                            ConnectIpsReactiveClient.class));
        }

        @Test
        @DisplayName("context fails when pfx-path is invalid")
        void invalidPfxPath_contextFails() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.connectips.merchant-id=123",
                            "nepalpay.connectips.app-id=APP-001",
                            "nepalpay.connectips.app-name=TestApp",
                            "nepalpay.connectips.app-password=password",
                            "nepalpay.connectips.pfx-path=file:/nonexistent.pfx",
                            "nepalpay.connectips.pfx-password=pfxpass"
                    )
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    // ── Multiple beans ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple reactive clients")
    class MultipleReactiveClients {

        @Test
        @DisplayName("Khalti and eSewa created when both keys present")
        void khaltiAndEsewaCreated() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_khalti_key",
                            "nepalpay.khalti.return-url=https://example.com/cb",
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/s",
                            "nepalpay.esewa.failure-url=https://example.com/f"
                    )
                    .run(context -> {
                        assertThat(context)
                                .hasSingleBean(KhaltiReactiveClient.class);
                        assertThat(context)
                                .hasSingleBean(EsewaReactiveClient.class);
                    });
        }

        @Test
        @DisplayName("no beans created when no keys configured")
        void noBeansWhenNoKeys() {
            contextRunner
                    .run(context -> {
                        assertThat(context)
                                .doesNotHaveBean(KhaltiReactiveClient.class);
                        assertThat(context)
                                .doesNotHaveBean(EsewaReactiveClient.class);
                        assertThat(context)
                                .doesNotHaveBean(
                                        ConnectIpsReactiveClient.class);
                    });
        }

        @Test
        @DisplayName("only Khalti created when only Khalti key configured")
        void onlyKhaltiCreated() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb"
                    )
                    .run(context -> {
                        assertThat(context)
                                .hasSingleBean(KhaltiReactiveClient.class);
                        assertThat(context)
                                .doesNotHaveBean(EsewaReactiveClient.class);
                        assertThat(context)
                                .doesNotHaveBean(
                                        ConnectIpsReactiveClient.class);
                    });
        }
    }
}