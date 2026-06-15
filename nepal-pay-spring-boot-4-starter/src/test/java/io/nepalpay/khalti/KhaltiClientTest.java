package io.nepalpay.khalti;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for KhaltiClient using MockWebServer.
 *
 * No Spring context loaded — pure unit test.
 * MockWebServer simulates the Khalti API server locally.
 * No real internet or real API keys needed.
 */
@DisplayName("KhaltiClient")
class KhaltiClientTest {

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private static final String FAKE_SECRET_KEY = "test_secret_key_abc123";
    private static final String FAKE_PIDX       = "bZQLD9wRVWo4CdESSfuSsB";
    private static final String FAKE_ORDER_ID   = "ORD-TEST-001";
    private static final long   AMOUNT_PAISA    = 10000L; // NPR 100

    private MockWebServer mockWebServer;
    private KhaltiClient  khaltiClient;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws IOException {
        // Start a fresh MockWebServer before each test
        // Listens on a random available port — no port conflicts
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Build KhaltiClient pointed at MockWebServer URL
        // This is the key: we override the base URL so requests hit our mock server
        String mockServerUrl = mockWebServer.url("/").toString();

        NepalPayProperties.KhaltiProperties props = buildKhaltiProperties();

        // We use RestClient.Builder and override baseUrl to MockWebServer
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(mockServerUrl)
                .defaultHeader("Authorization", "Key " + FAKE_SECRET_KEY)
                .defaultHeader("Content-Type", "application/json");

        // Use a testable constructor that accepts a custom RestClient directly
        khaltiClient = new KhaltiClient(props, builder, mockServerUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Always shut down MockWebServer after each test
        mockWebServer.shutdown();
    }

    // ── initiatePayment() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("initiatePayment()")
    class InitiatePayment {

        @Test
        @DisplayName("success: returns pidx and paymentUrl")
        void initiatePayment_success() throws InterruptedException {
            // Arrange — queue a mock response from "Khalti"
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "payment_url": "https://pay.khalti.com/?pidx=bZQLD9wRVWo4CdESSfuSsB",
                                "expires_at": "2024-01-01T00:15:00Z",
                                "expires_in": 900
                            }
                            """));

            // Act
            KhaltiInitiateResponse response = khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com")
                            .build()
            );

            // Assert — response fields
            assertThat(response).isNotNull();
            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(response.paymentUrl()).contains("pay.khalti.com");
            assertThat(response.expiresIn()).isEqualTo(900);

            // Assert — correct HTTP request was sent to Khalti
            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/epayment/initiate/");
            assertThat(request.getHeader("Authorization"))
                    .isEqualTo("Key " + FAKE_SECRET_KEY);
            assertThat(request.getBody().readUtf8())
                    .contains(FAKE_ORDER_ID)
                    .contains("Test Product")
                    .contains(String.valueOf(AMOUNT_PAISA));
        }

        @Test
        @DisplayName("throws KhaltiException on 401 unauthorized")
        void initiatePayment_unauthorized_throwsKhaltiException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "detail": "Authentication credentials were not provided."
                            }
                            """));

            // Act + Assert
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com")
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti payment initiation failed")
                    .extracting("httpStatus")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("throws KhaltiException on 500 server error")
        void initiatePayment_serverError_throwsKhaltiException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            // Act + Assert
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com")
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti server error");
        }

        @Test
        @DisplayName("throws KhaltiException when amount is zero")
        void initiatePayment_zeroAmount_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(0L)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws KhaltiException when purchaseOrderId is blank")
        void initiatePayment_blankOrderId_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId("  ")
                            .purchaseOrderName("Test Product")
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("purchaseOrderId is required");
        }

        @Test
        @DisplayName("throws KhaltiException when purchaseOrderName is null")
        void initiatePayment_nullOrderName_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName(null)
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("purchaseOrderName is required");
        }

        @Test
        @DisplayName("throws KhaltiException when returnUrl missing from both request and properties")
        void initiatePayment_noReturnUrl_throwsKhaltiException() {
            // Props with no returnUrl, request with no returnUrl either
            NepalPayProperties.KhaltiProperties propsNoUrl =
                    new NepalPayProperties.KhaltiProperties(
                            FAKE_SECRET_KEY,
                            null,   // ← no returnUrl in properties
                            "https://example.com",
                            true,
                            10
                    );

            String mockUrl = mockWebServer.url("/").toString();
            RestClient.Builder builder = RestClient.builder().baseUrl(mockUrl);
            KhaltiClient client = new KhaltiClient(propsNoUrl, builder, mockUrl);

            assertThatThrownBy(() -> client.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test")
                            // no returnUrl set
                            .build()
            ))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("returnUrl is required");
        }
    }

    // ── lookupPayment() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("lookupPayment()")
    class LookupPayment {

        @Test
        @DisplayName("success: COMPLETED status → isPaymentSuccessful() = true")
        void lookupPayment_completed_isSuccessful() throws InterruptedException {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "status": "Completed",
                                "total_amount": 10000,
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "purchase_order_id": "ORD-TEST-001",
                                "purchase_order_name": "Test Product",
                                "fee": 0,
                                "refunded": false
                            }
                            """));

            // Act
            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);

            // Assert — response is correctly parsed
            assertThat(response).isNotNull();
            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(response.status()).isEqualTo("Completed");
            assertThat(response.totalAmount()).isEqualTo(10000L);
            assertThat(response.transactionId()).isEqualTo("GFq9DrfGSZQKjsj");
            assertThat(response.refunded()).isFalse();

            // Assert — convenience methods work correctly
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.COMPLETED);
            assertThat(response.isPaymentSuccessful()).isTrue();

            // Assert — correct HTTP request
            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/epayment/lookup/");
            assertThat(request.getBody().readUtf8()).contains(FAKE_PIDX);
        }

        @Test
        @DisplayName("Pending status → isPaymentSuccessful() = false")
        void lookupPayment_pending_isNotSuccessful() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "status": "Pending",
                                "total_amount": 10000
                            }
                            """));

            // Act
            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);

            // Assert
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.PENDING);
            assertThat(response.isPaymentSuccessful()).isFalse();
        }

        @Test
        @DisplayName("User canceled status → isTerminalFailure() = true")
        void lookupPayment_userCanceled_isTerminalFailure() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "status": "User canceled",
                                "total_amount": 10000
                            }
                            """));

            // Act
            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);

            // Assert
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.USER_CANCELED);
            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.paymentStatus().isTerminalFailure()).isTrue();
        }

        @Test
        @DisplayName("isAmountValid: matches expected amount → true")
        void lookupPayment_amountValid_returnsTrue() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "status": "Completed",
                                "total_amount": 10000
                            }
                            """));

            // Act
            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);

            // Assert — correct amount
            assertThat(response.isAmountValid(10000L)).isTrue();
            // Assert — tampered amount detected
            assertThat(response.isAmountValid(1L)).isFalse();
        }

        @Test
        @DisplayName("throws KhaltiException when pidx is null")
        void lookupPayment_nullPidx_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.lookupPayment(null))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("pidx cannot be null or blank");
        }

        @Test
        @DisplayName("throws KhaltiException when pidx is blank")
        void lookupPayment_blankPidx_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.lookupPayment("   "))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("pidx cannot be null or blank");
        }

        @Test
        @DisplayName("throws KhaltiException on 400 bad request")
        void lookupPayment_badRequest_throwsKhaltiException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": ["This field may not be blank."]
                            }
                            """));

            // Act + Assert
            assertThatThrownBy(() -> khaltiClient.lookupPayment(FAKE_PIDX))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti lookup failed")
                    .extracting("httpStatus")
                    .isEqualTo(400);
        }

        // ── refundPayment() ───────────────────────────────────────────────────────

        @Nested
        @DisplayName("refundPayment()")
        class RefundPayment {

            // ── Full refund ───────────────────────────────────────────────────────

            @Test
            @DisplayName("full refund: success → isRefundSuccessful() = true")
            void refundPayment_fullRefund_success() throws InterruptedException {
                // Arrange — mock Khalti refund API response
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded":       true,
                                "status":         "Refunded"
                            }
                            """));

                // Act
                KhaltiRefundResponse response =
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

                // Assert — response parsed correctly
                assertThat(response).isNotNull();
                assertThat(response.transactionId()).isEqualTo("GFq9DrfGSZQKjsj");
                assertThat(response.pidx()).isEqualTo("bZQLD9wRVWo4CdESSfuSsB");
                assertThat(response.refunded()).isTrue();
                assertThat(response.status()).isEqualTo("Refunded");

                // Assert — convenience methods
                assertThat(response.isRefundSuccessful()).isTrue();
                assertThat(response.paymentStatus())
                        .isEqualTo(KhaltiPaymentStatus.REFUNDED);

                // Assert — correct HTTP request was sent
                // Refund path: /api/merchant-transaction/{id}/refund/
                // NOT /epayment/initiate/ or /epayment/lookup/
                RecordedRequest request = mockWebServer.takeRequest();
                assertThat(request.getMethod()).isEqualTo("POST");
                assertThat(request.getPath())
                        .isEqualTo("/api/merchant-transaction/GFq9DrfGSZQKjsj/refund/");

                // Assert — Authorization header is present
                assertThat(request.getHeader("Authorization"))
                        .isEqualTo("Key " + FAKE_SECRET_KEY);
            }

            @Test
            @DisplayName("full refund: request body is empty — {}")
            void refundPayment_fullRefund_requestBodyIsEmpty()
                    throws InterruptedException {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded":       true,
                                "status":         "Refunded"
                            }
                            """));

                // Act
                khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

                // Assert — full refund sends empty body {}
                // NOT {"amount": xxx} — that is only for partial refund
                RecordedRequest request = mockWebServer.takeRequest();
                String requestBody = request.getBody().readUtf8();
                assertThat(requestBody).isEqualTo("{}");
            }

            // ── Partial refund ────────────────────────────────────────────────────

            @Test
            @DisplayName("partial refund: success → sends amount in paisa in body")
            void refundPayment_partialRefund_sendsAmountInBody()
                    throws InterruptedException {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded":       true,
                                "status":         "Refunded"
                            }
                            """));

                // Act — refund NPR 50 = 5000 paisa (from a NPR 100 transaction)
                KhaltiRefundResponse response =
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 5000L);

                // Assert — response is successful
                assertThat(response.isRefundSuccessful()).isTrue();

                // Assert — request body contains amount in paisa
                // {"amount": 5000}  NOT {"amount": 50} or {"amount": 100}
                RecordedRequest request = mockWebServer.takeRequest();
                String requestBody = request.getBody().readUtf8();
                assertThat(requestBody).contains("\"amount\"");
                assertThat(requestBody).contains("5000");
                assertThat(requestBody).doesNotContain("pidx");
            }

            @Test
            @DisplayName("partial refund: correct path — same as full refund")
            void refundPayment_partialRefund_usesCorrectPath()
                    throws InterruptedException {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "TXN-PARTIAL-001",
                                "refunded":       true,
                                "status":         "Refunded"
                            }
                            """));

                // Act
                khaltiClient.refundPayment("TXN-PARTIAL-001", 5000L);

                // Assert — path uses the transactionId, not pidx
                RecordedRequest request = mockWebServer.takeRequest();
                assertThat(request.getPath())
                        .isEqualTo("/api/merchant-transaction/TXN-PARTIAL-001/refund/");
            }

            // ── HTTP error handling ───────────────────────────────────────────────

            @Test
            @DisplayName("throws KhaltiException on 400 — payment not refundable")
            void refundPayment_badRequest_throwsKhaltiException() {
                // Arrange — Khalti returns 400 when payment is not Completed
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "detail": "Cannot refund this transaction"
                            }
                            """));

                // Act + Assert
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("Khalti refund failed")
                        .hasMessageContaining("Only Completed payments can be refunded")
                        .extracting("httpStatus")
                        .isEqualTo(400);
            }

            @Test
            @DisplayName("throws KhaltiException on 401 — invalid secret key")
            void refundPayment_unauthorized_throwsKhaltiException() {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "detail": "Authentication credentials were not provided."
                            }
                            """));

                // Act + Assert
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                        .isInstanceOf(KhaltiException.class)
                        .extracting("httpStatus")
                        .isEqualTo(401);
            }

            @Test
            @DisplayName("throws KhaltiException on 500 server error")
            void refundPayment_serverError_throwsKhaltiException() {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(500));

                // Act + Assert
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("Khalti server error during refund");
            }

            // ── Input validation ──────────────────────────────────────────────────

            @Test
            @DisplayName("throws KhaltiException when transactionId is null")
            void refundPayment_nullTransactionId_throwsKhaltiException() {
                // transactionId null means:
                // either payment was never completed (Pending/Expired/Canceled)
                // or developer passed pidx instead of transactionId by mistake
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment(null))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("transactionId cannot be null or blank")
                        .hasMessageContaining("lookupPayment()");
            }

            @Test
            @DisplayName("throws KhaltiException when transactionId is blank")
            void refundPayment_blankTransactionId_throwsKhaltiException() {
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("   "))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("transactionId cannot be null or blank");
            }

            @Test
            @DisplayName("throws KhaltiException when transactionId is null — partial overload")
            void refundPayment_partial_nullTransactionId_throwsKhaltiException() {
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment(null, 5000L))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("transactionId cannot be null or blank");
            }

            @Test
            @DisplayName("throws KhaltiException when amountPaisa is zero")
            void refundPayment_zeroAmountPaisa_throwsKhaltiException() {
                // Zero paisa partial refund makes no sense
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 0L))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("amountPaisa must be greater than 0")
                        .hasMessageContaining("full refund");
            }

            @Test
            @DisplayName("throws KhaltiException when amountPaisa is negative")
            void refundPayment_negativeAmountPaisa_throwsKhaltiException() {
                assertThatThrownBy(() ->
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj", -100L))
                        .isInstanceOf(KhaltiException.class)
                        .hasMessageContaining("amountPaisa must be greater than 0");
            }

            // ── KhaltiRefundResponse helpers ──────────────────────────────────────

            @Test
            @DisplayName("isRefundSuccessful() = false when refunded=false in response")
            void refundPayment_refundedFalseInResponse_isRefundSuccessfulFalse() {
                // Arrange — unusual but possible: API returns 200 but refunded=false
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded":       false,
                                "status":         "Pending"
                            }
                            """));

                // Act
                KhaltiRefundResponse response =
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

                // Assert — both conditions must be true for isRefundSuccessful()
                // refunded=false AND status≠Refunded → isRefundSuccessful()=false
                assertThat(response.isRefundSuccessful()).isFalse();
                assertThat(response.paymentStatus())
                        .isEqualTo(KhaltiPaymentStatus.PENDING);
            }

            @Test
            @DisplayName("paymentStatus() returns REFUNDED for successful refund response")
            void refundPayment_paymentStatus_returnsRefunded() {
                // Arrange
                mockWebServer.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                            {
                                "pidx":           "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded":       true,
                                "status":         "Refunded"
                            }
                            """));

                // Act
                KhaltiRefundResponse response =
                        khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

                // Assert
                assertThat(response.paymentStatus())
                        .isEqualTo(KhaltiPaymentStatus.REFUNDED);
            }
        }
    }

    // ── utility methods ───────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_returnsTrueWhenSandboxEnabled() {
        assertThat(khaltiClient.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("baseUrl() returns the configured URL")
    void baseUrl_returnsConfiguredUrl() {
        assertThat(khaltiClient.baseUrl()).isNotBlank();
    }

    @Test
    @DisplayName("baseDomain() returns non-blank value")
    void baseDomain_returnsNonBlankValue() {
        // baseDomain is used to build the refund URL
        // It must never be null or blank
        assertThat(khaltiClient.baseDomain()).isNotBlank();
    }

    @Test
    @DisplayName("baseDomain() does not end with slash")
    void baseDomain_doesNotEndWithSlash() {
        // Trailing slash would produce double-slash in refund URL:
        // "http://localhost:PORT//api/merchant-transaction/..."
        assertThat(khaltiClient.baseDomain()).doesNotEndWith("/");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private NepalPayProperties.KhaltiProperties buildKhaltiProperties() {
        return new NepalPayProperties.KhaltiProperties(
                FAKE_SECRET_KEY,
                "https://example.com/callback",
                "https://example.com",
                true,
                10
        );
    }
}