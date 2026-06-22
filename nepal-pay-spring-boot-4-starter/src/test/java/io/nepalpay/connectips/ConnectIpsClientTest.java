package io.nepalpay.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.core.retry.RetryProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ConnectIpsClient.
 *
 * <p>ConnectIpsClient now caches the RSA PrivateKey at construction time.
 * Tests use a JVM-generated throwaway RSA key pair via the package-private
 * test constructor — no real .pfx file is needed.
 *
 * <p>Validation tests (empty pfx, blank password) assert on the constructor
 * since validation now happens at startup (fail fast), not at payment time.
 */
@DisplayName("ConnectIpsClient")
class ConnectIpsClientTest {

    // ── Constants ─────────────────────────────────────────────────────────
    private static final int    MERCHANT_ID   = 123;
    private static final String APP_ID        = "TEST-APP-001";
    private static final String APP_NAME      = "TestApp";
    private static final String APP_PASSWORD  = "testAppPassword";
    private static final String REFERENCE_ID  = "REF-ORDER-001";
    private static final long   TXN_AMT_PAISA = 10000L;
    private static final String MOCK_TOKEN    = "MOCK_RSA_TOKEN_FOR_TESTING";

    // ── Shared test RSA key — generated ONCE for the entire test class ────
    private static PrivateKey TEST_PRIVATE_KEY;

    // ── Per-test instances ────────────────────────────────────────────────
    private MockWebServer    mockWebServer;
    private ConnectIpsClient clientMockServer;
    private ConnectIpsClient retryConnectIpsClient;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generate a throwaway RSA key pair once for the entire test class.
     *
     * <p>512-bit is intentionally weak — this key is only used to satisfy
     * {@code Signature.initSign()} in tests. It provides no real security
     * and is discarded after the test run.
     *
     * <p>{@code @BeforeAll} means this runs once, not before every test,
     * keeping the test suite fast.
     */
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

        // Use package-private test constructor — injects pre-built key directly.
        // No .pfx file needed for HTTP behavior tests.
        clientMockServer = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                true,
                RestClient.builder(),
                mockWebServer.url("/").toString(),
                null);

        RetryProperties fastRetry = new RetryProperties(true, 2, 0L, 1.0, 0L);
        retryConnectIpsClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                true,
                RestClient.builder(),
                mockWebServer.url("/").toString(),
                fastRetry);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR VALIDATION
    // (validation now happens at startup — fail fast — not at method call)
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constructor validation (fail fast)")
    class ConstructorValidation {

        @Test
        @DisplayName("throws when pfx bytes are empty")
        void constructor_emptyPfxBytes_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    new ConnectIpsClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[0], "somePassword",
                            true, RestClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("throws when pfx bytes are null")
        void constructor_nullPfxBytes_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    new ConnectIpsClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            null, "somePassword",
                            true, RestClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("throws when pfx password is blank")
        void constructor_blankPfxPassword_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    new ConnectIpsClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[]{1, 2, 3}, "",
                            true, RestClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("pfx password not configured");
        }

        @Test
        @DisplayName("throws when pfx password is null")
        void constructor_nullPfxPassword_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    new ConnectIpsClient(
                            MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                            new byte[]{1, 2, 3}, null,
                            true, RestClient.builder()))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("pfx password not configured");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ConnectIpsPaymentRequest BUILDER
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("builder: sets all fields correctly")
        void builder_setsAllFields() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .txnAmtPaisa(10000L)
                    .referenceId("ORD-001")
                    .remarks("Test payment")
                    .particulars("NepalPay")
                    .build();

            assertThat(request.txnId()).isEqualTo("TXN-001");
            assertThat(request.txnAmtPaisa()).isEqualTo(10000L);
            assertThat(request.referenceId()).isEqualTo("ORD-001");
            assertThat(request.remarks()).isEqualTo("Test payment");
            assertThat(request.particulars()).isEqualTo("NepalPay");
        }

        @Test
        @DisplayName("amountNPR() converts NPR to paisa correctly")
        void amountNPR_convertsToPaisa() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .amountNPR(100L)
                    .referenceId("ORD-001")
                    .build();

            assertThat(request.txnAmtPaisa()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("remarks and particulars default to empty string")
        void remarksAndParticulars_defaultToEmpty() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .txnAmtPaisa(5000L)
                    .referenceId("ORD-001")
                    .build();

            assertThat(request.remarks()).isEmpty();
            assertThat(request.particulars()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateTransactionWithToken()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateTransactionWithToken()")
    class ValidateTransactionWithToken {

        @Test
        @DisplayName("SUCCESS status → isPaymentSuccessful() = true")
        void success_statusIsSuccessful() throws InterruptedException {
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

            ConnectIpsValidateResponse response =
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.referenceId()).isEqualTo(REFERENCE_ID);
            assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(response.statusDesc()).isEqualTo("TRANSACTION SUCCESSFUL");
            assertThat(response.isPaymentSuccessful()).isTrue();

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath())
                    .isEqualTo("/connectipswebws/api/creditor/validatetxn");
            assertThat(request.getHeader("Authorization")).startsWith("Basic ");
            assertThat(request.getBody().readUtf8())
                    .contains(REFERENCE_ID)
                    .contains(String.valueOf(TXN_AMT_PAISA))
                    .contains(MOCK_TOKEN);
        }

        @Test
        @DisplayName("FAILED status → isPaymentSuccessful() = false")
        void failed_isNotSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "merchantId": 123,
                                "appId": "TEST-APP-001",
                                "referenceId": "REF-ORDER-001",
                                "txnAmt": "10000",
                                "status": "FAILED",
                                "statusDesc": "TRANSACTION FAILED"
                            }
                            """));

            ConnectIpsValidateResponse response =
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.paymentStatus())
                    .isEqualTo(io.nepalpay.core.connectips.model
                            .ConnectIpsPaymentStatus.FAILED);
        }

        @Test
        @DisplayName("throws ConnectIpsException on 400 bad request")
        void badRequest_throwsConnectIpsException() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"error": "Invalid merchantId"}
                            """));

            assertThatThrownBy(() ->
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("ConnectIPS validation failed")
                    .extracting("httpStatus").isEqualTo(400);
        }

        @Test
        @DisplayName("throws ConnectIpsException on 500 server error")
        void serverError_throwsConnectIpsException() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() ->
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("ConnectIPS server error");
        }

        @Test
        @DisplayName("Basic Auth header is correctly encoded appId:appPassword")
        void basicAuth_isCorrectlyEncoded() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "referenceId": "REF-ORDER-001",
                                "status": "SUCCESS",
                                "statusDesc": "OK"
                            }
                            """));

            clientMockServer.validateTransactionWithToken(
                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            RecordedRequest request = mockWebServer.takeRequest();
            String expectedCredentials = java.util.Base64.getEncoder()
                    .encodeToString((APP_ID + ":" + APP_PASSWORD)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(request.getHeader("Authorization"))
                    .isEqualTo("Basic " + expectedCredentials);
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
        void retryOnce_succeeds() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
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

            ConnectIpsValidateResponse response =
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response.isPaymentSuccessful()).isTrue();
            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);

            mockWebServer.takeRequest();
            RecordedRequest successReq = mockWebServer.takeRequest();
            assertThat(successReq.getPath())
                    .isEqualTo("/connectipswebws/api/creditor/validatetxn");
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
                            {
                                "referenceId": "REF-ORDER-001",
                                "status": "SUCCESS",
                                "statusDesc": "TRANSACTION SUCCESSFUL"
                            }
                            """));

            ConnectIpsValidateResponse response =
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response.isPaymentSuccessful()).isTrue();
            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("exhausts all retries and throws")
        void exhaustsRetries_throws() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() ->
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("does NOT retry on 400")
        void doesNotRetry_on400() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("{\"error\": \"Invalid merchantId\"}"));

            assertThatThrownBy(() ->
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("does NOT retry on 401")
        void doesNotRetry_on401() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setBody("{\"error\": \"Unauthorized\"}"));

            assertThatThrownBy(() ->
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry disabled by default — only 1 request on 5xx")
        void retryDisabledByDefault_onlyOneRequest() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() ->
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_returnsTrue_whenSandboxEnabled() {
        assertThat(clientMockServer.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("isSandbox() returns false when sandbox=false")
    void isSandbox_returnsFalse_whenProductionMode() {
        ConnectIpsClient prodClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                false,
                RestClient.builder(),
                "https://connectips.com",
                RetryProperties.DEFAULT);          // ← was null

        assertThat(prodClient.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("formActionUrl() returns UAT gateway URL in sandbox mode")
    void formActionUrl_returnsUatUrl_inSandboxMode() {
        assertThat(clientMockServer.formActionUrl())
                .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
    }

    @Test
    @DisplayName("formActionUrl() returns production URL when sandbox=false")
    void formActionUrl_returnsProductionUrl_whenSandboxFalse() {
        ConnectIpsClient prodClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                TEST_PRIVATE_KEY,
                false,
                RestClient.builder(),
                "https://connectips.com",
                RetryProperties.DEFAULT);          // ← was null

        assertThat(prodClient.formActionUrl())
                .isEqualTo(
                        "https://connectips.com/connectipswebgw/loginpage");
    }
}