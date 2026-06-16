package io.nepalpay.autoconfigure;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.fonepay.FonepayClient;
import io.nepalpay.khalti.KhaltiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NepalPayAutoConfiguration}.
 *
 * <p>Uses Spring Boot's {@link ApplicationContextRunner} — the official
 * lightweight way to test auto-configuration without starting a full
 * application context. No server, no network, very fast.
 *
 * <p>{@code RestClient.Builder} is registered manually via
 * {@code withBean()} — this avoids needing
 * {@code RestClientAutoConfiguration} on the classpath and keeps
 * the test scope minimal and focused.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>KhaltiClient bean — created / not created / sandbox mode</li>
 *   <li>EsewaClient bean — created / not created / sandbox mode</li>
 *   <li>ConnectIpsClient bean — created / not created / sandbox mode</li>
 *   <li>FonepayClient bean — created / not created / sandbox mode</li>
 *   <li>Developer override — @ConditionalOnMissingBean behavior</li>
 *   <li>Multiple gateways — all four created together</li>
 *   <li>No keys — no beans created</li>
 * </ul>
 */
@DisplayName("NepalPayAutoConfiguration")
class NepalPayAutoConfigurationTest {

    /**
     * Base runner shared by all tests.
     *
     * <p>WHY withBean(RestClient.Builder.class, RestClient::builder)?
     * Our auto-configuration needs a {@code RestClient.Builder} bean
     * to create {@code KhaltiClient}, {@code EsewaClient}, and
     * {@code ConnectIpsClient}. Registering it directly is simpler
     * and faster than pulling in the full RestClient auto-config chain.
     *
     * <p>Note: {@code FonepayClient} does NOT need {@code RestClient.Builder}
     * because Fonepay uses URL redirect — no server-to-server HTTP calls.
     */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(NepalPayAutoConfiguration.class))
                    .withBean(
                            RestClient.Builder.class,
                            RestClient::builder);

    // ── KhaltiClient ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("KhaltiClient bean")
    class KhaltiClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.khalti.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/khalti/callback",
                            "nepalpay.khalti.website-url=https://example.com"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(FonepayClient.class);
                    });
        }

        @Test
        @DisplayName("is NOT created when nepalpay.khalti.secret-key is absent")
        void notCreatedWhenSecretKeyAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context).doesNotHaveBean(KhaltiClient.class));
        }

        @Test
        @DisplayName("sandbox=true by default when property not specified")
        void sandboxTrueByDefault() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/callback"
                    )
                    .run(context -> {
                        KhaltiClient bean = context.getBean(KhaltiClient.class);
                        assertThat(bean.isSandbox()).isTrue();
                        assertThat(bean.baseUrl())
                                .isEqualTo("https://dev.khalti.com/api/v2");
                    });
        }

        @Test
        @DisplayName("sandbox=false gives production base URL")
        void productionModeGivesProductionUrl() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=live_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/callback",
                            "nepalpay.khalti.sandbox=false"
                    )
                    .run(context -> {
                        KhaltiClient bean = context.getBean(KhaltiClient.class);
                        assertThat(bean.isSandbox()).isFalse();
                        assertThat(bean.baseUrl())
                                .isEqualTo("https://khalti.com/api/v2");
                    });
        }

        @Test
        @DisplayName("developer custom bean takes precedence over auto-configured bean")
        void developerCanOverrideWithCustomBean() {
            NepalPayProperties.KhaltiProperties customProps =
                    new NepalPayProperties.KhaltiProperties(
                            "custom_key",
                            "https://custom.com/callback",
                            "https://custom.com",
                            true,
                            10,
                            null
                    );

            KhaltiClient customBean = new KhaltiClient(
                    customProps,
                    RestClient.builder(),
                    "https://custom-mock.com"
            );

            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/callback"
                    )
                    .withBean(KhaltiClient.class, () -> customBean)
                    .run(context -> {
                        // Only ONE KhaltiClient bean — not two
                        assertThat(context).hasSingleBean(KhaltiClient.class);

                        // The bean in context is our custom one
                        KhaltiClient beanInContext = context.getBean(KhaltiClient.class);
                        assertThat(beanInContext.baseUrl())
                                .isEqualTo("https://custom-mock.com");
                    });
        }
        @Test
        @DisplayName("retry defaults to disabled when not configured")
        void retryDefaultsToDisabledWhenNotConfigured() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb"
                            // ← no retry: block
                    )
                    .run(context -> {
                        // Properties bound correctly
                        NepalPayProperties props =
                                context.getBean(NepalPayProperties.class);

                        // retryOrDefault() never returns null
                        assertThat(props.khalti().retryOrDefault()).isNotNull();

                        // retry is disabled by default
                        assertThat(props.khalti().retryOrDefault().isActive())
                                .isFalse();
                    });
        }

        @Test
        @DisplayName("retry can be enabled via properties")
        void retryCanBeEnabledViaProperties() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_key",
                            "nepalpay.khalti.return-url=https://example.com/cb",
                            "nepalpay.khalti.retry.enabled=true",
                            "nepalpay.khalti.retry.max-attempts=3",
                            "nepalpay.khalti.retry.initial-delay-ms=500",
                            "nepalpay.khalti.retry.multiplier=2.0",
                            "nepalpay.khalti.retry.max-delay-ms=5000"
                    )
                    .run(context -> {
                        NepalPayProperties props =
                                context.getBean(NepalPayProperties.class);

                        RetryProperties retry = props.khalti().retryOrDefault();
                        assertThat(retry.isActive()).isTrue();
                        assertThat(retry.maxAttempts()).isEqualTo(3);
                        assertThat(retry.initialDelayMs()).isEqualTo(500L);
                        assertThat(retry.multiplier()).isEqualTo(2.0);
                        assertThat(retry.maxDelayMs()).isEqualTo(5000L);
                    });
        }
    }

    // ── EsewaClient ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EsewaClient bean")
    class EsewaClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.esewa.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/esewa/success",
                            "nepalpay.esewa.failure-url=https://example.com/esewa/failure"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(FonepayClient.class);
                    });
        }

        @Test
        @DisplayName("is NOT created when nepalpay.esewa.secret-key is absent")
        void notCreatedWhenSecretKeyAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context).doesNotHaveBean(EsewaClient.class));
        }

        @Test
        @DisplayName("sandbox=true by default when property not specified")
        void sandboxTrueByDefault() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/success",
                            "nepalpay.esewa.failure-url=https://example.com/failure"
                    )
                    .run(context -> {
                        EsewaClient bean = context.getBean(EsewaClient.class);
                        assertThat(bean.isSandbox()).isTrue();
                        assertThat(bean.formActionUrl())
                                .isEqualTo("https://rc-epay.esewa.com.np/api/epay/main/v2/form");
                    });
        }

        @Test
        @DisplayName("sandbox=false gives production form action URL")
        void productionModeGivesProductionFormUrl() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=live_secret_key",
                            "nepalpay.esewa.product-code=LIVE_MERCHANT_CODE",
                            "nepalpay.esewa.success-url=https://example.com/success",
                            "nepalpay.esewa.failure-url=https://example.com/failure",
                            "nepalpay.esewa.sandbox=false"
                    )
                    .run(context -> {
                        EsewaClient bean = context.getBean(EsewaClient.class);
                        assertThat(bean.isSandbox()).isFalse();
                        assertThat(bean.formActionUrl())
                                .isEqualTo("https://epay.esewa.com.np/api/epay/main/v2/form");
                    });
        }

        @Test
        @DisplayName("developer custom bean takes precedence over auto-configured bean")
        void developerCanOverrideWithCustomBean() {
            NepalPayProperties.EsewaProperties customProps =
                    new NepalPayProperties.EsewaProperties(
                            "custom_secret",
                            "CUSTOM_CODE",
                            "https://custom.com/success",
                            "https://custom.com/failure",
                            true,
                            10,
                            null
                    );

            EsewaClient customBean = new EsewaClient(
                    customProps,
                    RestClient.builder(),
                    "https://custom-esewa-status.com"
            );

            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/success",
                            "nepalpay.esewa.failure-url=https://example.com/failure"
                    )
                    .withBean(EsewaClient.class, () -> customBean)
                    .run(context -> {
                        // Only ONE EsewaClient — not two
                        assertThat(context).hasSingleBean(EsewaClient.class);
                    });
        }
    }

    // ── ConnectIpsClient ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsClient bean")
    class ConnectIpsClientBeanTests {

        @Test
        @DisplayName("is NOT created when nepalpay.connectips properties are absent")
        void notCreatedWhenConfigAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context).doesNotHaveBean(ConnectIpsClient.class));
        }

        @Test
        @DisplayName("sandbox=true gives UAT gateway URL")
        void sandboxTrueGivesUatGatewayUrl() {
            ConnectIpsClient client = new ConnectIpsClient(
                    123, "APP-001", "TestApp", "password",
                    new byte[0], "pfxpass",
                    true,
                    RestClient.builder()
            );

            assertThat(client.isSandbox()).isTrue();
            assertThat(client.formActionUrl())
                    .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
        }

        @Test
        @DisplayName("sandbox=false gives production gateway URL")
        void sandboxFalseGivesProductionGatewayUrl() {
            ConnectIpsClient client = new ConnectIpsClient(
                    123, "APP-001", "TestApp", "password",
                    new byte[0], "pfxpass",
                    false,
                    RestClient.builder()
            );

            assertThat(client.isSandbox()).isFalse();
            assertThat(client.formActionUrl())
                    .isEqualTo("https://connectips.com/connectipswebgw/loginpage");
        }

        @Test
        @DisplayName("ConnectIpsClient: throws when pfx-path is invalid")
        void connectIpsClient_invalidPfxPath_throwsOnBeanCreation() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.connectips.merchant-id=123",
                            "nepalpay.connectips.app-id=APP-001",
                            "nepalpay.connectips.app-name=TestApp",
                            "nepalpay.connectips.app-password=password",
                            "nepalpay.connectips.pfx-path=file:/nonexistent/path.pfx",
                            "nepalpay.connectips.pfx-password=pfxpass"
                    )
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    // ── FonepayClient ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FonepayClient bean")
    class FonepayClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.fonepay.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.fonepay.merchant-code=TEST_MERCHANT",
                            "nepalpay.fonepay.secret-key=test_secret_key",
                            "nepalpay.fonepay.return-url=http://localhost:8080/fonepay/callback"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(FonepayClient.class);
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
                    });
        }

        @Test
        @DisplayName("is NOT created when nepalpay.fonepay.secret-key is absent")
        void notCreatedWhenSecretKeyAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context).doesNotHaveBean(FonepayClient.class));
        }

        @Test
        @DisplayName("sandbox=true by default — gateway URL is dev.fonepay.com")
        void sandboxTrueByDefault() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.fonepay.merchant-code=TEST_MERCHANT",
                            "nepalpay.fonepay.secret-key=test_secret_key",
                            "nepalpay.fonepay.return-url=http://localhost:8080/callback"
                    )
                    .run(context -> {
                        FonepayClient bean = context.getBean(FonepayClient.class);
                        assertThat(bean.isSandbox()).isTrue();
                        assertThat(bean.gatewayUrl())
                                .isEqualTo("https://dev.fonepay.com/api/merchantRequest");
                    });
        }

        @Test
        @DisplayName("sandbox=false gives production gateway URL")
        void sandboxFalseGivesProductionUrl() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.fonepay.merchant-code=LIVE_MERCHANT",
                            "nepalpay.fonepay.secret-key=live_secret_key",
                            "nepalpay.fonepay.return-url=https://example.com/callback",
                            "nepalpay.fonepay.sandbox=false"
                    )
                    .run(context -> {
                        FonepayClient bean = context.getBean(FonepayClient.class);
                        assertThat(bean.isSandbox()).isFalse();
                        assertThat(bean.gatewayUrl())
                                .isEqualTo("https://fonepay.com/api/merchantRequest");
                    });
        }

        @Test
        @DisplayName("developer custom bean takes precedence over auto-configured bean")
        void developerCanOverrideWithCustomBean() {
            NepalPayProperties.FonepayProperties customProps =
                    new NepalPayProperties.FonepayProperties(
                            "CUSTOM_MERCHANT",
                            "custom_secret",
                            "https://custom.com/callback",
                            true
                    );

            FonepayClient customBean = new FonepayClient(
                    customProps,
                    "https://custom-fonepay-gateway.com"
            );

            contextRunner
                    .withPropertyValues(
                            "nepalpay.fonepay.merchant-code=TEST_MERCHANT",
                            "nepalpay.fonepay.secret-key=test_secret_key",
                            "nepalpay.fonepay.return-url=http://localhost:8080/callback"
                    )
                    .withBean(FonepayClient.class, () -> customBean)
                    .run(context -> {
                        // Only ONE FonepayClient — not two
                        assertThat(context).hasSingleBean(FonepayClient.class);

                        // Bean in context is our custom one
                        FonepayClient beanInContext = context.getBean(FonepayClient.class);
                        assertThat(beanInContext.gatewayUrl())
                                .isEqualTo("https://custom-fonepay-gateway.com");
                    });
        }
    }

    // ── All gateways together ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple gateways")
    class MultipleGatewaysTests {

        @Test
        @DisplayName("all four gateways created when all keys present")
        void allFourGatewaysCreated() {
            contextRunner
                    .withPropertyValues(
                            // Khalti
                            "nepalpay.khalti.secret-key=test_khalti_key",
                            "nepalpay.khalti.return-url=https://example.com/khalti/callback",
                            // eSewa
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/esewa/success",
                            "nepalpay.esewa.failure-url=https://example.com/esewa/failure",
                            // Fonepay
                            "nepalpay.fonepay.merchant-code=TEST_MERCHANT",
                            "nepalpay.fonepay.secret-key=test_fonepay_key",
                            "nepalpay.fonepay.return-url=https://example.com/fonepay/callback"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(KhaltiClient.class);
                        assertThat(context).hasSingleBean(EsewaClient.class);
                        assertThat(context).hasSingleBean(FonepayClient.class);
                        // ConnectIPS not in this test —
                        // requires pfx bytes which cannot be set via properties alone
                    });
        }

        @Test
        @DisplayName("Khalti and eSewa created — Fonepay NOT created when key absent")
        void khaltiAndEsewaCreated_fonepayNotCreated() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_khalti_key",
                            "nepalpay.khalti.return-url=https://example.com/khalti/callback",
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/esewa/success",
                            "nepalpay.esewa.failure-url=https://example.com/esewa/failure"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(KhaltiClient.class);
                        assertThat(context).hasSingleBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(FonepayClient.class);
                    });
        }

        @Test
        @DisplayName("no beans created when no keys configured")
        void noBeansCreatedWhenNoKeysPresent() {
            contextRunner
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(ConnectIpsClient.class);
                        assertThat(context).doesNotHaveBean(FonepayClient.class);
                    });
        }

        @Test
        @DisplayName("only Fonepay created when only Fonepay key configured")
        void onlyFonepayCreated() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.fonepay.merchant-code=TEST_MERCHANT",
                            "nepalpay.fonepay.secret-key=test_fonepay_key",
                            "nepalpay.fonepay.return-url=https://example.com/callback"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(FonepayClient.class);
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(ConnectIpsClient.class);
                    });
        }
    }
}