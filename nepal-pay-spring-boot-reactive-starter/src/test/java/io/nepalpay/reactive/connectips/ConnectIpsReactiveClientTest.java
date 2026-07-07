package io.nepalpay.reactive.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
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
 *
 * <p>ConnectIpsReactiveClient caches the RSA PrivateKey at construction time.
 * Tests use a JVM-generated throwaway RSA key pair via the package-private
 * test constructor — no real .pfx file is needed.
 *
 * <p>Uses {@link StepVerifier} (reactor-test) for all reactive assertions.
 * Uses {@link MockWebServer} (OkHttp) to mock HTTP responses.
 *
 * <p>Key reactive pattern:
 * <pre>{@code
 * StepVerifier.create(client.validateTransactionWithToken(...))
 *     .expectNextMatches(res -> res.isPaymentSuccessful())
 *     .verifyComplete();
 * }</pre>
 */
@DisplayName("ConnectIpsReactiveClient")
class ConnectIpsReactiveClientTest {

    // ── Constants ─────────────────────────────────────────────────────────
    private static final int    MERCHANT_ID   = 123;
    private static final String APP_ID        = "TEST-APP-001";
    private static final String APP_NAME      = "TestApp";
    private static final String APP_PASSWORD  = "testAppPassword";
    private static final String REFERENCE_ID  = "REF-ORDER-001";
    private static final long   TXN_AMT_PAISA = 10000L;
    private static final String MOCK_TOKEN    = "MOCK_RSA_TOKEN_FOR_TESTING";

    /**
     * Throwaway RSA key pair — generated ONCE for the entire test class.
     *
     * <p>2048-bit is used per security best practices.
     * 512-bit is deprecated in modern JVMs and triggers security warnings.
     *
     * <p>{@code @BeforeAll} runs once — keeps the test suite fast despite
     * the slightly higher cost of 2048-bit generation (~50-100ms).
     */
    // 512 → 2048 — avoids JVM security deprecation warnings
    private static PrivateKey TEST_PRIVATE_KEY;

    // ── Per-test instances ────────────────────────────────────────────────
    private MockWebServer             mockWebServer;
    private ConnectIpsReactiveClient  clientMockServer;
    private ConnectIpsReactiveClient  retryClient;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @BeforeAll
    static void generateTestKey() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        //  was 512-bit which triggers JVM security warnings
        keyGen.initialize(2048);
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

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR VALIDATION (fail fast)
    // ─────────────────────────────────────────────────────────────────────

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
        @DisplayName("throws when pfx bytes are null")
        void constructor_nullPfx_throws() {
            assertThatThrownBy(() ->
                    new ConnectIpsReactiveClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            null, "password",
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

        @Test
        @DisplayName("throws when pfx password is null")
        void constructor_nullPassword_throws() {
            assertThatThrownBy(() ->
                    new ConnectIpsReactiveClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[]{1, 2, 3}, null,
                            true, WebClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("pfx password not configured");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildFormPayload() — synchronous, no HTTP
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFormPayload()")
    class BuildFormPayload {

        @Test
        @DisplayName("success: returns signed payload with all fields populated")
        void buildFormPayload_success_allFieldsSet() {
            // Synchronous — no StepVerifier needed
            ConnectIpsFormPayload payload = clientMockServer.buildFormPayload(
                    ConnectIpsPaymentRequest.builder()
                            .txnId("TXN-001")
                            .amountNPR(100L)
                            .referenceId("ORD-001")
                            .remarks("Test")
                            .particulars("NepalPay")
                            .build());

            assertThat(payload).isNotNull();
            assertThat(payload.txnId()).isEqualTo("TXN-001");
            assertThat(payload.txnAmt()).isEqualTo(10000L);   // 100 NPR → paisa
            assertThat(payload.referenceId()).isEqualTo("ORD-001");
            assertThat(payload.remarks()).isEqualTo("Test");
            assertThat(payload.particulars()).isEqualTo("NepalPay");
            assertThat(payload.token()).isNotBlank();          // RSA signed
            assertThat(payload.formActionUrl())
                    .contains("uat.connectips.com");           // sandbox URL
        }

        @Test
        @DisplayName("throws ConnectIpsException when request is null")
        void buildFormPayload_nullRequest_throws() {
            assertThatThrownBy(() ->
                    clientMockServer.buildFormPayload(
                            (ConnectIpsPaymentRequest) null))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("ConnectIpsPaymentRequest cannot be null");
        }

        @Test
        @DisplayName("throws ConnectIpsException when txnId is blank")
        void buildFormPayload_blankTxnId_throws() {
            assertThatThrownBy(() ->
                    clientMockServer.buildFormPayload(
                            "  ", 10000L, "ORD-001", "", ""))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("txnId is required");
        }

        @Test
        @DisplayName("throws ConnectIpsException when referenceId is blank")
        void buildFormPayload_blankReferenceId_throws() {
            assertThatThrownBy(() ->
                    clientMockServer.buildFormPayload(
                            "TXN-001", 10000L, "  ", "", ""))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("referenceId is required");
        }

        @Test
        @DisplayName("throws ConnectIpsException when txnAmtPaisa is zero")
        void buildFormPayload_zeroAmount_throws() {
            assertThatThrownBy(() ->
                    clientMockServer.buildFormPayload(
                            "TXN-001", 0L, "ORD-001", "", ""))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("txnAmtPaisa must be greater than 0");
        }

        @Test
        @DisplayName("throws ConnectIpsException when txnAmtPaisa is negative")
        void buildFormPayload_negativeAmount_throws() {
            assertThatThrownBy(() ->
                    clientMockServer.buildFormPayload(
                            "TXN-001", -500L, "ORD-001", "", ""))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("txnAmtPaisa must be greater than 0");
        }

        @Test
        @DisplayName("no HTTP call made during buildFormPayload")
        void buildFormPayload_makesNoHttpCall() {
            clientMockServer.buildFormPayload(
                    ConnectIpsPaymentRequest.builder()
                            .txnId("TXN-001")
                            .amountNPR(100L)
                            .referenceId("ORD-001")
                            .build());

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateTransaction() — reactive, includes RSA signing
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateTransaction() input validation")
    class ValidateTransactionValidation {

        @Test
        @DisplayName("emits ConnectIpsException when txnId is blank")
        void validateTransaction_blankTxnId_emitsError() {
            // validateTransaction wraps everything in Mono.defer —
            // validation errors are now emitted as Mono.error signals, not thrown.
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "  ", REFERENCE_ID, TXN_AMT_PAISA))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains("txnId is required"))
                    .verify();

            // No HTTP request — rejected before RSA signing
            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits ConnectIpsException when txnId is null")
        void validateTransaction_nullTxnId_emitsError() {
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    null, REFERENCE_ID, TXN_AMT_PAISA))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains("txnId is required"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits ConnectIpsException when referenceId is blank")
        void validateTransaction_blankReferenceId_emitsError() {
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "TXN-001", "  ", TXN_AMT_PAISA))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains("referenceId is required"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits ConnectIpsException when referenceId is null")
        void validateTransaction_nullReferenceId_emitsError() {
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "TXN-001", null, TXN_AMT_PAISA))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains("referenceId is required"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits ConnectIpsException when txnAmtPaisa is zero")
        void validateTransaction_zeroAmount_emitsError() {
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "TXN-001", REFERENCE_ID, 0L))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains(
                                    "txnAmtPaisa must be greater than 0"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits ConnectIpsException when txnAmtPaisa is negative")
        void validateTransaction_negativeAmount_emitsError() {
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "TXN-001", REFERENCE_ID, -500L))
                    .expectErrorMatches(err ->
                            err instanceof ConnectIpsException
                                    && err.getMessage().contains(
                                    "txnAmtPaisa must be greater than 0"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("success: emits validated response when all inputs are valid")
        void validateTransaction_validInputs_success() {
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

            // validateTransaction signs with real RSA key — still hits mock server
            StepVerifier.create(
                            clientMockServer.validateTransaction(
                                    "TXN-001", REFERENCE_ID, TXN_AMT_PAISA))
                    .expectNextMatches(res ->
                            res.isPaymentSuccessful()
                                    && res.status().equals("SUCCESS"))
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateTransactionWithToken() — reactive, skips RSA signing
    // ─────────────────────────────────────────────────────────────────────

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
        @DisplayName("Basic Auth header correctly encoded as appId:appPassword")
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

    // ─────────────────────────────────────────────────────────────────────
    // RETRY BEHAVIOR
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("retries on 5xx and succeeds on second attempt")
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
                    .expectNextMatches(ConnectIpsValidateResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("retries twice and succeeds on third attempt")
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
                    .expectNextMatches(ConnectIpsValidateResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("exhausts all retries and emits ConnectIpsException")
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
        @DisplayName("does NOT retry on 401")
        void doesNotRetry_on401() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setBody("{\"error\": \"Unauthorized\"}"));

            StepVerifier.create(
                            retryClient.validateTransactionWithToken(
                                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .expectError(ConnectIpsException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry disabled by default — only 1 request on 5xx")
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

    // ─────────────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // ─────────────────────────────────────────────────────────────────────

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
                RetryProperties.DEFAULT);

        assertThat(prod.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("formActionUrl() returns UAT gateway URL in sandbox mode")
    void formActionUrl_uatInSandbox() {
        assertThat(clientMockServer.formActionUrl())
                .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
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
                RetryProperties.DEFAULT);

        assertThat(prod.formActionUrl())
                .isEqualTo("https://connectips.com/connectipswebgw/loginpage");
    }

    // ─────────────────────────────────────────────────────────────────────
    // ConnectIpsPaymentRequest BUILDER
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("amountNPR() converts NPR to paisa correctly")
        void amountNPR_convertsToPaisa() {
            ConnectIpsPaymentRequest req = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .amountNPR(100L)
                    .referenceId("ORD-001")
                    .build();

            assertThat(req.txnAmtPaisa()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("remarks and particulars default to empty string")
        void defaults_emptyStrings() {
            ConnectIpsPaymentRequest req = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .txnAmtPaisa(5000L)
                    .referenceId("ORD-001")
                    .build();

            assertThat(req.remarks()).isEmpty();
            assertThat(req.particulars()).isEmpty();
        }

        @Test
        @DisplayName("builder sets all fields correctly")
        void builder_setsAllFields() {
            ConnectIpsPaymentRequest req = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .txnAmtPaisa(10000L)
                    .referenceId("ORD-001")
                    .remarks("Test payment")
                    .particulars("NepalPay")
                    .build();

            assertThat(req.txnId()).isEqualTo("TXN-001");
            assertThat(req.txnAmtPaisa()).isEqualTo(10000L);
            assertThat(req.referenceId()).isEqualTo("ORD-001");
            assertThat(req.remarks()).isEqualTo("Test payment");
            assertThat(req.particulars()).isEqualTo("NepalPay");
        }
    }
}