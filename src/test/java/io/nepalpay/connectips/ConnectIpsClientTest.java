package io.nepalpay.connectips;

import io.nepalpay.connectips.exception.ConnectIpsException;
import io.nepalpay.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.connectips.model.ConnectIpsValidateResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ConnectIpsClient.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Missing config validation (empty .pfx bytes / blank password)</li>
 *   <li>Token message correctness (no real .pfx needed)</li>
 *   <li>validateTransactionWithToken() HTTP call via MockWebServer</li>
 *   <li>Success and failure response parsing</li>
 *   <li>HTTP error handling (4xx, 5xx)</li>
 *   <li>isSandbox() and formActionUrl() utility methods</li>
 * </ul>
 *
 * <p>NOTE: Full RSA token generation ({@code buildFormPayload} and
 * {@code validateTransaction}) requires a real NCHL .pfx certificate.
 * Those paths are covered by config-guard tests here.
 * End-to-end RSA signing must be tested with a real UAT certificate.
 */
@DisplayName("ConnectIpsClient")
class ConnectIpsClientTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int    MERCHANT_ID  = 123;
    private static final String APP_ID       = "TEST-APP-001";
    private static final String APP_NAME     = "TestApp";
    private static final String APP_PASSWORD = "testAppPassword";
    private static final String REFERENCE_ID = "REF-ORDER-001";
    private static final long   TXN_AMT_PAISA = 10000L;
    private static final String MOCK_TOKEN   = "MOCK_RSA_TOKEN_FOR_TESTING";

    // ── Test clients ──────────────────────────────────────────────────────────

    private MockWebServer    mockWebServer;
    private ConnectIpsClient clientNoPfx;      // config guard tests
    private ConnectIpsClient clientMockServer; // HTTP tests via MockWebServer

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Client with empty pfx — used to test config validation guards
        // Uses 8-arg production constructor
        clientNoPfx = new ConnectIpsClient(
                MERCHANT_ID,
                APP_ID,
                APP_NAME,
                APP_PASSWORD,
                new byte[0],     // ← empty pfx — triggers validation error
                "somePassword",
                true,
                RestClient.builder()
        );

        // Client with MockWebServer — used to test HTTP calls
        // Uses 9-arg test constructor so validate API hits MockWebServer
        clientMockServer = new ConnectIpsClient(
                MERCHANT_ID,
                APP_ID,
                APP_NAME,
                APP_PASSWORD,
                new byte[0],     // pfx not needed — we use validateTransactionWithToken
                "somePassword",
                true,
                RestClient.builder(),
                mockWebServer.url("/").toString()  // ← MockWebServer URL
        );
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
                    clientNoPfx.buildFormPayload(
                            "TXN-001",
                            TXN_AMT_PAISA,
                            REFERENCE_ID,
                            "Test remarks",
                            "Test particulars"))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("buildFormPayload (request overload): throws when pfx bytes are empty")
        void buildFormPayload_requestOverload_emptyPfx_throwsConnectIpsException() {
            ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
                    .txnId("TXN-001")
                    .txnAmtPaisa(TXN_AMT_PAISA)
                    .referenceId(REFERENCE_ID)
                    .remarks("Test remarks")
                    .particulars("Test particulars")
                    .build();

            assertThatThrownBy(() -> clientNoPfx.buildFormPayload(request))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("validateTransaction: throws when pfx bytes are empty")
        void validateTransaction_emptyPfx_throwsConnectIpsException() {
            assertThatThrownBy(() ->
                    clientNoPfx.validateTransaction(
                            "TXN-001", REFERENCE_ID, TXN_AMT_PAISA))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining(".pfx certificate bytes are empty");
        }

        @Test
        @DisplayName("buildFormPayload: throws when pfx password is blank")
        void buildFormPayload_blankPfxPassword_throwsConnectIpsException() {
            ConnectIpsClient clientBlankPassword = new ConnectIpsClient(
                    MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                    new byte[]{1, 2, 3},  // non-empty pfx bytes
                    "",                   // ← blank pfx password
                    true,
                    RestClient.builder()
            );

            assertThatThrownBy(() ->
                    clientBlankPassword.buildFormPayload(
                            "TXN-001", TXN_AMT_PAISA, REFERENCE_ID, "", ""))
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
                    .amountNPR(100L)      // NPR 100 → 10000 paisa
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

    // ── validateTransactionWithToken() via MockWebServer ──────────────────────

    @Nested
    @DisplayName("validateTransactionWithToken()")
    class ValidateTransactionWithToken {

        @Test
        @DisplayName("SUCCESS status → isPaymentSuccessful() = true")
        void success_statusIsSuccessful() throws InterruptedException {
            // Arrange
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

            // Act
            ConnectIpsValidateResponse response =
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            // Assert — response parsed correctly
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.referenceId()).isEqualTo(REFERENCE_ID);
            assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(response.statusDesc()).isEqualTo("TRANSACTION SUCCESSFUL");
            assertThat(response.isPaymentSuccessful()).isTrue();

            // Assert — correct HTTP request sent to ConnectIPS
            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath())
                    .isEqualTo("/connectipswebws/api/creditor/validatetxn");
            assertThat(request.getHeader("Authorization"))
                    .startsWith("Basic ");
            assertThat(request.getBody().readUtf8())
                    .contains(REFERENCE_ID)
                    .contains(String.valueOf(TXN_AMT_PAISA))
                    .contains(MOCK_TOKEN);
        }

        @Test
        @DisplayName("FAILED status → isPaymentSuccessful() = false")
        void failed_isNotSuccessful() {
            // Arrange
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

            // Act
            ConnectIpsValidateResponse response =
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            // Assert
            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.paymentStatus())
                    .isEqualTo(io.nepalpay.connectips.model.ConnectIpsPaymentStatus.FAILED);
        }

        @Test
        @DisplayName("throws ConnectIpsException on 400 bad request")
        void badRequest_throwsConnectIpsException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"error": "Invalid merchantId"}
                            """));

            // Act + Assert
            assertThatThrownBy(() ->
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("ConnectIPS validation failed")
                    .extracting("httpStatus")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("throws ConnectIpsException on 500 server error")
        void serverError_throwsConnectIpsException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(500));

            // Act + Assert
            assertThatThrownBy(() ->
                    clientMockServer.validateTransactionWithToken(
                            REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN))
                    .isInstanceOf(ConnectIpsException.class)
                    .hasMessageContaining("ConnectIPS server error");
        }

        @Test
        @DisplayName("Basic Auth header is correctly encoded appId:appPassword")
        void basicAuth_isCorrectlyEncoded() throws InterruptedException {
            // Arrange
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

            // Act
            clientMockServer.validateTransactionWithToken(
                    REFERENCE_ID, TXN_AMT_PAISA, MOCK_TOKEN);

            // Assert — Basic auth is Base64(appId:appPassword)
            RecordedRequest request = mockWebServer.takeRequest();
            String expectedCredentials = java.util.Base64.getEncoder()
                    .encodeToString((APP_ID + ":" + APP_PASSWORD)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertThat(request.getHeader("Authorization"))
                    .isEqualTo("Basic " + expectedCredentials);
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
                new byte[0], "somePassword",
                false,
                RestClient.builder()
        );
        assertThat(prodClient.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("formActionUrl() returns UAT gateway URL in sandbox mode")
    void formActionUrl_returnsUatUrl_inSandboxMode() {
        assertThat(clientNoPfx.formActionUrl())
                .isEqualTo("https://uat.connectips.com/connectipswebgw/loginpage");
    }

    @Test
    @DisplayName("formActionUrl() returns production gateway URL when sandbox=false")
    void formActionUrl_returnsProductionUrl_whenSandboxFalse() {
        ConnectIpsClient prodClient = new ConnectIpsClient(
                MERCHANT_ID, APP_ID, APP_NAME, APP_PASSWORD,
                new byte[0], "somePassword",
                false,
                RestClient.builder()
        );
        assertThat(prodClient.formActionUrl())
                .isEqualTo("https://connectips.com/connectipswebgw/loginpage");
    }
}