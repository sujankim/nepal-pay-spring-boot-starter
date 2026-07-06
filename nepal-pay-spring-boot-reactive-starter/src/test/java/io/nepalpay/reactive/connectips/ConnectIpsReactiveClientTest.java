package io.nepalpay.reactive.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.core.retry.RetryProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConnectIpsReactiveClient}.
 */
@DisplayName("ConnectIpsReactiveClient")
class ConnectIpsReactiveClientTest {

    private static final int    MERCHANT_ID   = 123;
    private static final String APP_ID        = "TEST-APP-001";
    private static final String APP_NAME      = "TestApp";
    private static final String APP_PASSWORD  = "testAppPassword";
    private static final String REFERENCE_ID  = "REF-ORDER-001";
    private static final long   TXN_AMT_PAISA = 10000L;
    private static final String MOCK_TOKEN    = "MOCK_RSA_TOKEN_FOR_TESTING";

    private static PrivateKey TEST_PRIVATE_KEY;

    private MockWebServer             mockWebServer;
    private ConnectIpsReactiveClient  clientMockServer;
    private ConnectIpsReactiveClient  retryClient;

    @BeforeAll
    static void generateTestKey() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(512);
        TEST_PRIVATE_KEY = keyGen.generateKeyPair().getPrivate();
    }

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        clientMockServer = new ConnectIpsReactiveClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                true,
                WebClient.builder(),
                mockWebServer.url("/").toString(),
                null);

        RetryProperties fastRetry =
                new RetryProperties(true, 2, 0L, 1.0, 0L);
        retryClient = new ConnectIpsReactiveClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                true,
                WebClient.builder(),
                mockWebServer.url("/").toString(),
                fastRetry);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── Constructor validation ────────────────────────────────────────────

    @Nested
    @DisplayName("Constructor validation (fail fast)")
    class ConstructorValidation {

        @Test
        @DisplayName("throws when pfx bytes are empty")
        void constructor_emptyPfx_throws() {
            assertThatThrownBy(() ->
                    new ConnectIpsReactiveClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[0], "password",
                            true, WebClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("throws when pfx password is blank")
        void constructor_blankPassword_throws() {
            assertThatThrownBy(() ->
                    new ConnectIpsReactiveClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[]{1, 2, 3}, "",
                            true, WebClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("pfx password not configured");
        }
    }

    // ── validateTransactionWithToken() ────────────────────────────────────

    @Nested
    @DisplayName("validateTransactionWithToken()")
    class ValidateTransactionWithToken {

        @Test
        @DisplayName("SUCCESS → isPaymentSuccessful() = true")
        void success_isPaymentSuccessful() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "merchantId": 123,
                                "appId": "TEST-APP-001",
                                "referenceId": "REF-ORDER-001",
                                "txnAmt": "10000",
                                "status": "SUCCESS",
                                "statusDesc": "TRANSACTION SUCCESSFUL"
                            }
                            """));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectNextMatches(res ->
                            res.isPaymentSuccessful()
                                    && res.status().equals("SUCCESS")
                                    && res.referenceId().equals(REFERENCE_ID)
                                    && res.merchantId().equals(MERCHANT_ID))
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath())
                    .isEqualTo("/connectipswebws/api/creditor/validatetxn");
            assertThat(req.getHeader("Authorization")).startsWith("Basic ");
            assertThat(req.getBody().readUtf8())
                    .contains(REFERENCE_ID)
                    .contains(String.valueOf(TXN_AMT_PAISA))
                    .contains(MOCK_TOKEN);
        }

        @Test
        @DisplayName("FAILED → isPaymentSuccessful() = false")
        void failed_notSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"merchantId":123,"referenceId":"REF-ORDER-001","status":"FAILED","statusDesc":"FAILED"}
                            """));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectNextMatches(res -> !res.isPaymentSuccessful())
                    .verifyComplete();
        }

        @Test
        @DisplayName("emits ConnectIpsException on 400")
        void badRequest_emitsError() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"error": "Invalid merchantId"}
                            """));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException ce
                                    && ce.httpStatus() == 400
                                    && err.getMessage().contains(
                                    "ConnectIPS validation failed"))
                    .verify();
        }

        @Test
        @DisplayName("emits ConnectIpsException on 500")
        void serverError_emitsError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException ce
                                    && ce.httpStatus() == 500
                                    && err.getMessage().contains(
                                    "ConnectIPS server error"))
                    .verify();
        }

        @Test
        @DisplayName("Basic Auth header correctly encoded")
        void basicAuth_correctlyEncoded() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"referenceId":"REF-ORDER-001","status":"SUCCESS","statusDesc":"OK"}
                            """));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectNextCount(1)
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            String expectedAuth = java.util.Base64.getEncoder()
                    .encodeToString((APP_ID + ":" + APP_PASSWORD)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(req.getHeader("Authorization"))
                    .isEqualTo("Basic " + expectedAuth);
        }
    }

    // ── Retry behavior ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("retries on 5xx and succeeds")
        void retryOnce_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"referenceId":"REF-ORDER-001","status":"SUCCESS","statusDesc":"SUCCESSFUL"}
                            """));

            StepVerifier.create(
                            retryClient.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectNextMatches(
                            ConnectIpsValidateResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("retries twice and succeeds")
        void retryTwice_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"referenceId":"REF-ORDER-001","status":"SUCCESS","statusDesc":"SUCCESSFUL"}
                            """));

            StepVerifier.create(
                            retryClient.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectNextMatches(
                            ConnectIpsValidateResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("exhausts retries and emits error")
        void exhaustsRetries_emitsError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            retryClient.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectError(ConnectIpsException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("does NOT retry on 400")
        void doesNotRetry_on400() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("{\"error\": \"Invalid merchantId\"}"));

            StepVerifier.create(
                            retryClient.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectError(ConnectIpsException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry disabled by default — 1 request on 5xx")
        void retryDisabled_onlyOneRequest() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            clientMockServer.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectError(ConnectIpsException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true in sandbox mode")
    void isSandbox_true() {
        assertThat(clientMockServer.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("isSandbox() returns false in production mode")
    void isSandbox_false() {
        ConnectIpsReactiveClient prod = new ConnectIpsReactiveClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                false,
                WebClient.builder(),
                "https://connectips.com",
                null);

        assertThat(prod.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("formActionUrl() returns UAT URL in sandbox mode")
    void formActionUrl_uatInSandbox() {
        assertThat(clientMockServer.formActionUrl())
                .isEqualTo(
                        "https://uat.connectips.com/connectipswebgw/loginpage");
    }

    @Test
    @DisplayName("formActionUrl() returns production URL when sandbox=false")
    void formActionUrl_productionWhenNotSandbox() {
        ConnectIpsReactiveClient prod = new ConnectIpsReactiveClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                false,
                WebClient.builder(),
                "https://connectips.com",
                null);

        assertThat(prod.formActionUrl())
                .isEqualTo(
                        "https://connectips.com/connectipswebgw/loginpage");
    }

    // ── ConnectIpsPaymentRequest builder ──────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("amountNPR() converts to paisa correctly")
        void amountNPR_convertsToPaisa() {
            ConnectIpsPaymentRequest req =
                    ConnectIpsPaymentRequest.builder()
                            .txnId("TXN-001")
                            .amountNPR(100L)
                            .referenceId("ORD-001")
                            .build();

            assertThat(req.txnAmtPaisa()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("remarks and particulars default to empty string")
        void defaults_emptyStrings() {
            ConnectIpsPaymentRequest req =
                    ConnectIpsPaymentRequest.builder()
                            .txnId("TXN-001")
                            .txnAmtPaisa(5000L)
                            .referenceId("ORD-001")
                            .build();

            assertThat(req.remarks()).isEmpty();
            assertThat(req.particulars()).isEmpty();
        }
    }
}