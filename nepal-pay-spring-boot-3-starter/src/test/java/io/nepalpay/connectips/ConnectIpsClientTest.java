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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ConnectIpsClient — Spring Boot 3.
 */
@DisplayName("ConnectIpsClient")
class ConnectIpsClientTest {

    private static final int    MERCHANT_ID   = 123;
    private static final String APP_ID        = "TEST-APP-001";
    private static final String APP_NAME      = "TestApp";
    private static final String APP_PASSWORD  = "testAppPassword";
    private static final String REFERENCE_ID  = "REF-ORDER-001";
    private static final long   TXN_AMT_PAISA = 10000L;
    private static final String MOCK_TOKEN    = "MOCK_RSA_TOKEN_FOR_TESTING";

    private MockWebServer    mockWebServer;
    private ConnectIpsClient clientNoPfx;
    private ConnectIpsClient clientMockServer;
    private ConnectIpsClient retryConnectIpsClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        clientNoPfx = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword", true, RestClient.builder());

        clientMockServer = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword", true, RestClient.builder(),
                mockWebServer.url("/").toString());

        RetryProperties fastRetry = new RetryProperties(true, 2, 0L, 1.0, 0L);
        retryConnectIpsClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword", true, RestClient.builder(),
                mockWebServer.url("/").toString(), fastRetry);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── Config validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config validation")
    class ConfigValidation {

        @Test
        @DisplayName("buildFormPayload: throws when pfx bytes are empty")
        void buildFormPayload_emptyPfx_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    clientNoPfx.buildFormPayload("TXN-001", TXN_AMT_PAISA,
                            REFERENCE_ID, "Test remarks", "Test particulars"))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("buildFormPayload (request overload): throws when pfx bytes are empty")
        void buildFormPayload_requestOverload_emptyPfx_throwsConnectIpsException() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001").txnAmtPaisa(TXN_AMT_PAISA)
                    .referenceId(REFERENCE_ID).build();

            assertThatThrownBy(() -> clientNoPfx.buildFormPayload(request))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("validateTransaction: throws when pfx bytes are empty")
        void validateTransaction_emptyPfx_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    clientNoPfx.validateTransaction("TXN-001", REFERENCE_ID, TXN_AMT_PAISA))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("buildFormPayload: throws when pfx password is blank")
        void buildFormPayload_blankPfxPassword_throwsConnectIpsException() {
            ConnectIpsClient clientBlankPassword = new ConnectIpsClient(
                    MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                    new byte[]{1, 2, 3}, "", true, RestClient.builder());

            assertThatThrownBy(() ->
                    clientBlankPassword.buildFormPayload("TXN-001", TXN_AMT_PAISA, REFERENCE_ID, "", ""))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("pfx password not configured");
        }
    }

    // ── ConnectIpsPaymentRequest builder ──────────────────────────────────────

    @Nested
    @DisplayName("ConnectIpsPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("builder: sets all fields correctly")
        void builder_setsAllFields() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001").txnAmtPaisa(10000L)
                    .referenceId("ORD-001").remarks("Test payment").particulars("NepalPay").build();

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
                    .txnId("TXN-001").amountNPR(100L).referenceId("ORD-001").build();
            assertThat(request.txnAmtPaisa()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("remarks and particulars default to empty string")
        void remarksAndParticulars_defaultToEmpty() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001").txnAmtPaisa(5000L).referenceId("ORD-001").build();
            assertThat(request.remarks()).isEmpty();
            assertThat(request.particulars()).isEmpty();
        }
    }

    // ── validateTransactionWithToken() ────────────────────────────────────────

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
                            {"merchantId":123,"appId":"TEST-APP-001","referenceId":"REF-ORDER-001","txnAmt":"10000","status":"FAILED","statusDesc":"TRANSACTION FAILED"}
                            """));

            ConnectIpsValidateResponse response =
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.paymentStatus())
                    .isEqualTo(io.nepalpay.core.connectips.model.ConnectIpsPaymentStatus.FAILED);
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
                            {"referenceId":"REF-ORDER-001","status":"SUCCESS","statusDesc":"OK"}
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

    // ── Retry behavior ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("validateTransactionWithToken: retries on 5xx and succeeds")
        void validateTransactionWithToken_retryOnce_succeeds() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"merchantId":123,"appId":"TEST-APP-001","referenceId":"REF-ORDER-001","txnAmt":"10000","status":"SUCCESS","statusDesc":"TRANSACTION SUCCESSFUL"}
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
        @DisplayName("validateTransactionWithToken: retries twice and succeeds")
        void validateTransactionWithToken_retryTwice_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"referenceId":"REF-ORDER-001","status":"SUCCESS","statusDesc":"TRANSACTION SUCCESSFUL"}
                            """));

            ConnectIpsValidateResponse response =
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            assertThat(response.isPaymentSuccessful()).isTrue();
            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("validateTransactionWithToken: exhausts retries and throws")
        void validateTransactionWithToken_exhaustsRetries_throws() {
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
        @DisplayName("validateTransactionWithToken: does NOT retry on 400")
        void validateTransactionWithToken_doesNotRetry_on400() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400).setBody("{\"error\": \"Invalid merchantId\"}"));

            assertThatThrownBy(() ->
                    retryConnectIpsClient.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("validateTransactionWithToken: does NOT retry on 401")
        void validateTransactionWithToken_doesNotRetry_on401() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401).setBody("{\"error\": \"Unauthorized\"}"));

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

    // ── Utility methods ───────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_returnsTrue_whenSandboxEnabled() {
        assertThat(clientNoPfx.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("isSandbox() returns false when sandbox=false")
    void isSandbox_returnsFalse_whenProductionMode() {
        ConnectIpsClient prodClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword", false, RestClient.builder());
        assertThat(prodClient.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("formActionUrl() returns UAT gateway URL in sandbox mode")
    void formActionUrl_returnsUatUrl_inSandboxMode() {
        assertThat(clientNoPfx.formActionUrl())
                .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
    }

    @Test
    @DisplayName("formActionUrl() returns production URL when sandbox=false")
    void formActionUrl_returnsProductionUrl_whenSandboxFalse() {
        ConnectIpsClient prodClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword", false, RestClient.builder());
        assertThat(prodClient.formActionUrl())
                .isEqualTo("https://connectips.com/connectipswebgw/loginpage");
    }
}