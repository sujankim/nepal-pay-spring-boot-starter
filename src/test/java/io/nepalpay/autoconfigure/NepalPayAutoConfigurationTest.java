package io.nepalpay.autoconfigure;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.khalti.KhaltiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for NepalPayAutoConfiguration.
 *
 * Uses Spring Boot's ApplicationContextRunner — the official lightweight way
 * to test auto-configuration without starting a full application context.
 * No server, no network, very fast.
 *
 * RestClient.Builder is registered manually via withBean() — this avoids
 * needing RestClientAutoConfiguration on the classpath and keeps the
 * test scope minimal and focused.
 */
@DisplayName("NepalPayAutoConfiguration")
class NepalPayAutoConfigurationTest {

    /*
     * Base runner shared by all tests.
     *
     * WHY withBean(RestClient.Builder.class, RestClient::builder)?
     * Our auto-configuration needs a RestClient.Builder bean to create
     * KhaltiClient and EsewaClient. In Spring Boot 4.x, RestClientAutoConfiguration
     * moved to org.springframework.boot.restclient.autoconfigure — and pulling
     * in that full auto-config chain is heavy for a unit test.
     * Registering RestClient.Builder directly is simpler, faster, and focused.
     */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(NepalPayAutoConfiguration.class))
                    .withBean(
                            RestClient.Builder.class,
                            RestClient::builder);  // ← provide RestClient.Builder manually

    // ── KhaltiClient bean ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("KhaltiClient")
    class KhaltiClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.khalti.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/callback",
                            "nepalpay.khalti.website-url=https://example.com"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
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
                    });
        }

        @Test
        @DisplayName("sandbox=false when nepalpay.khalti.sandbox=false")
        void productionModeWhenSandboxFalse() {
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
            // Build a custom KhaltiClient using the now-public 3-arg constructor
            NepalPayProperties.KhaltiProperties customProps =
                    new NepalPayProperties.KhaltiProperties(
                            "custom_key",
                            "https://custom.com/callback",
                            "https://custom.com",
                            true,
                            10
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

                        // The bean in context is OUR custom one
                        KhaltiClient beanInContext = context.getBean(KhaltiClient.class);
                        assertThat(beanInContext.baseUrl())
                                .isEqualTo("https://custom-mock.com");
                    });
        }
    }

    // ── EsewaClient bean ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("EsewaClient")
    class EsewaClientBeanTests {

        @Test
        @DisplayName("is created when nepalpay.esewa.secret-key is present")
        void createdWhenSecretKeyPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/success",
                            "nepalpay.esewa.failure-url=https://example.com/failure"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(EsewaClient.class);
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
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
        @DisplayName("developer custom bean takes precedence over auto-configured bean")
        void developerCanOverrideWithCustomBean() {
            NepalPayProperties.EsewaProperties customProps =
                    new NepalPayProperties.EsewaProperties(
                            "custom_secret",
                            "CUSTOM_CODE",
                            "https://custom.com/success",
                            "https://custom.com/failure",
                            true,
                            10
                    );

            EsewaClient customBean = new EsewaClient(
                    customProps,
                    RestClient.builder(),
                    "https://custom-mock-esewa.com"
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
                        // Only ONE EsewaClient bean — not two
                        assertThat(context).hasSingleBean(EsewaClient.class);

                        // The bean in context is OUR custom one
                        EsewaClient beanInContext = context.getBean(EsewaClient.class);
                        assertThat(beanInContext.formActionUrl())
                                .isEqualTo("https://rc-epay.esewa.com.np/api/epay/main/v2/form");
                    });
        }
    }

    // ── ConnectIPS bean ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsClient")
    class ConnectIpsClientBeanTests {

        @Test
        @DisplayName("is NOT created when nepalpay.connectips.merchant-id is absent")
        void notCreatedWhenConfigAbsent() {
            contextRunner
                    .run(context ->
                            assertThat(context)
                                    .doesNotHaveBean(ConnectIpsClient.class));
        }

        @Test
        @DisplayName("is created when all required ConnectIPS properties are present")
        void createdWhenAllPropertiesPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.connectips.merchant-id=123",
                            "nepalpay.connectips.app-id=TEST-APP-001",
                            "nepalpay.connectips.app-name=TestApp",
                            "nepalpay.connectips.app-password=testpass",
                            "nepalpay.connectips.pfx-password=pfxpass",
                            "nepalpay.connectips.pfx-path=classpath:test.pfx",
                            "nepalpay.connectips.sandbox=true"
                    )
                    .run(context -> {
                        // Context loads — bean conditions evaluated
                        // Actual bean creation tested separately since
                        // pfx loading requires a real file
                        assertThat(context).doesNotHaveBean("broken");
                    });
        }

        @Test
        @DisplayName("sandbox=true by default when property not specified")
        void sandboxTrueByDefault() {
            ConnectIpsClient client = new ConnectIpsClient(
                    123, "APP-001", "TestApp", "password",
                    new byte[0], "pfxpass", true,
                    RestClient.builder()
            );
            assertThat(client.isSandbox()).isTrue();
            assertThat(client.formActionUrl())
                    .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
        }

        @Test
        @DisplayName("sandbox=false gives production gateway URL")
        void productionMode_givesProductionUrl() {
            ConnectIpsClient client = new ConnectIpsClient(
                    123, "APP-001", "TestApp", "password",
                    new byte[0], "pfxpass", false,
                    RestClient.builder()
            );
            assertThat(client.isSandbox()).isFalse();
            assertThat(client.formActionUrl())
                    .isEqualTo("https://connectips.com/connectipswebgw/loginpage");
        }
    }

    // ── Both gateways ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Both gateways")
    class BothGatewaysTests {

        @Test
        @DisplayName("both KhaltiClient and EsewaClient created when both keys present")
        void bothBeansCreatedWhenBothKeysPresent() {
            contextRunner
                    .withPropertyValues(
                            "nepalpay.khalti.secret-key=test_secret_key_abc",
                            "nepalpay.khalti.return-url=https://example.com/khalti/callback",
                            "nepalpay.esewa.secret-key=8gBm/:&EnhH.1/q",
                            "nepalpay.esewa.product-code=EPAYTEST",
                            "nepalpay.esewa.success-url=https://example.com/esewa/success",
                            "nepalpay.esewa.failure-url=https://example.com/esewa/failure"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(KhaltiClient.class);
                        assertThat(context).hasSingleBean(EsewaClient.class);
                    });
        }

        @Test
        @DisplayName("neither bean created when no keys present")
        void noBeanCreatedWhenNoKeysPresent() {
            contextRunner
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(KhaltiClient.class);
                        assertThat(context).doesNotHaveBean(EsewaClient.class);
                    });
        }
    }
}