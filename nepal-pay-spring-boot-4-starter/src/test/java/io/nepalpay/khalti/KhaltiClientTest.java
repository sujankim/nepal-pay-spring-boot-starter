package io.nepalpay.khalti;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiPaymentStatus;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
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
 * Unit tests for KhaltiClient — Spring Boot 4.
 *
 * <p>Tests:
 * initiatePayment(), lookupPayment(), refundPayment(), retry behavior.
 */
@DisplayName("KhaltiClient")
class KhaltiClientTest {

    private static final String FAKE_SECRET_KEY = "test_secret_key_abc123";
    private static final String FAKE_PIDX       = "bZQLD9wRVWo4CdESSfuSsB";
    private static final String FAKE_ORDER_ID   = "ORD-TEST-001";
    private static final long   AMOUNT_PAISA    = 10000L;

    private MockWebServer mockWebServer;
    private KhaltiClient  khaltiClient;
    private KhaltiClient  retryClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String mockServerUrl = mockWebServer.url("/").toString();
        NepalPayProperties.KhaltiProperties props = buildKhaltiProperties(null);

        khaltiClient = new KhaltiClient(props, RestClient.builder(), mockServerUrl);

        // Fast retry client — 0ms delay so tests run instantly
        RetryProperties fastRetry = new RetryProperties(true, 2, 0L, 1.0, 0L);
        NepalPayProperties.KhaltiProperties retryProps = buildKhaltiProperties(fastRetry);
        retryClient = new KhaltiClient(retryProps, RestClient.builder(), mockServerUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── initiatePayment() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("initiatePayment()")
    class InitiatePayment {

        @Test
        @DisplayName("success: returns pidx and paymentUrl")
        void initiatePayment_success() throws InterruptedException {
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

            KhaltiInitiateResponse response = khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA)
                            .purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com")
                            .build());

            assertThat(response).isNotNull();
            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(response.paymentUrl()).contains("pay.khalti.com");
            assertThat(response.expiresIn()).isEqualTo(900);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/epayment/initiate/");
            assertThat(request.getHeader("Authorization")).isEqualTo("Key " + FAKE_SECRET_KEY);
            assertThat(request.getBody().readUtf8())
                    .contains(FAKE_ORDER_ID).contains("Test Product")
                    .contains(String.valueOf(AMOUNT_PAISA));
        }

        @Test
        @DisplayName("throws KhaltiException on 401 unauthorized")
        void initiatePayment_unauthorized_throwsKhaltiException() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"detail": "Authentication credentials were not provided."}
                            """));

            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com").build()))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti payment initiation failed")
                    .extracting("httpStatus").isEqualTo(401);
        }

        @Test
        @DisplayName("throws KhaltiException on 500 server error")
        void initiatePayment_serverError_throwsKhaltiException() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback")
                            .websiteUrl("https://example.com").build()))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti server error");
        }

        @Test
        @DisplayName("throws KhaltiException when amount is zero")
        void initiatePayment_zeroAmount_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(0L).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product").build()))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws KhaltiException when purchaseOrderId is blank")
        void initiatePayment_blankOrderId_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId("  ")
                            .purchaseOrderName("Test Product").build()))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("purchaseOrderId is required");
        }

        @Test
        @DisplayName("throws KhaltiException when purchaseOrderName is null")
        void initiatePayment_nullOrderName_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName(null).build()))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("purchaseOrderName is required");
        }

        @Test
        @DisplayName("throws KhaltiException when returnUrl missing from both request and properties")
        void initiatePayment_noReturnUrl_throwsKhaltiException() {
            NepalPayProperties.KhaltiProperties propsNoUrl =
                    new NepalPayProperties.KhaltiProperties(
                            FAKE_SECRET_KEY, null, "https://example.com", true, 10, null);

            String mockUrl = mockWebServer.url("/").toString();
            KhaltiClient client = new KhaltiClient(propsNoUrl, RestClient.builder(), mockUrl);

            assertThatThrownBy(() -> client.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test").build()))
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

            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);

            assertThat(response).isNotNull();
            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(response.status()).isEqualTo("Completed");
            assertThat(response.totalAmount()).isEqualTo(10000L);
            assertThat(response.transactionId()).isEqualTo("GFq9DrfGSZQKjsj");
            assertThat(response.refunded()).isFalse();
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.COMPLETED);
            assertThat(response.isPaymentSuccessful()).isTrue();

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/epayment/lookup/");
            assertThat(request.getBody().readUtf8()).contains(FAKE_PIDX);
        }

        @Test
        @DisplayName("Pending status → isPaymentSuccessful() = false")
        void lookupPayment_pending_isNotSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": "bZQLD9wRVWo4CdESSfuSsB", "status": "Pending", "total_amount": 10000}
                            """));

            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.PENDING);
            assertThat(response.isPaymentSuccessful()).isFalse();
        }

        @Test
        @DisplayName("User canceled status → isTerminalFailure() = true")
        void lookupPayment_userCanceled_isTerminalFailure() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": "bZQLD9wRVWo4CdESSfuSsB", "status": "User canceled", "total_amount": 10000}
                            """));

            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.USER_CANCELED);
            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.paymentStatus().isTerminalFailure()).isTrue();
        }

        @Test
        @DisplayName("Refunded status → isRefunded() = true")
        void lookupPayment_refunded_isRefunded() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": "bZQLD9wRVWo4CdESSfuSsB", "status": "Refunded",
                             "total_amount": 10000, "refunded": true}
                            """));

            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.REFUNDED);
            assertThat(response.isPaymentSuccessful()).isFalse();
            assertThat(response.isRefunded()).isTrue();
        }

        @Test
        @DisplayName("isAmountValid: matches expected amount → true")
        void lookupPayment_amountValid_returnsTrue() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": "bZQLD9wRVWo4CdESSfuSsB", "status": "Completed", "total_amount": 10000}
                            """));

            KhaltiLookupResponse response = khaltiClient.lookupPayment(FAKE_PIDX);
            assertThat(response.isAmountValid(10000L)).isTrue();
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
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": ["This field may not be blank."]}
                            """));

            assertThatThrownBy(() -> khaltiClient.lookupPayment(FAKE_PIDX))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti lookup failed")
                    .extracting("httpStatus").isEqualTo(400);
        }
    }

    // ── refundPayment() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("refundPayment()")
    class RefundPayment {

        @Test
        @DisplayName("full refund: success → isRefundSuccessful() = true")
        void refundPayment_fullRefund_success() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "transaction_id": "GFq9DrfGSZQKjsj",
                                "refunded": true,
                                "status": "Refunded"
                            }
                            """));

            KhaltiRefundResponse response = khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

            assertThat(response).isNotNull();
            assertThat(response.transactionId()).isEqualTo("GFq9DrfGSZQKjsj");
            assertThat(response.pidx()).isEqualTo("bZQLD9wRVWo4CdESSfuSsB");
            assertThat(response.refunded()).isTrue();
            assertThat(response.status()).isEqualTo("Refunded");
            assertThat(response.isRefundSuccessful()).isTrue();
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.REFUNDED);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath())
                    .isEqualTo("/api/merchant-transaction/GFq9DrfGSZQKjsj/refund/");
            assertThat(request.getHeader("Authorization")).isEqualTo("Key " + FAKE_SECRET_KEY);
        }

        @Test
        @DisplayName("full refund: request body is empty — {}")
        void refundPayment_fullRefund_requestBodyIsEmpty() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":true,"status":"Refunded"}
                            """));

            khaltiClient.refundPayment("GFq9DrfGSZQKjsj");

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getBody().readUtf8()).isEqualTo("{}");
        }

        @Test
        @DisplayName("partial refund: sends amount in paisa in body")
        void refundPayment_partialRefund_sendsAmountInBody() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":true,"status":"Refunded"}
                            """));

            KhaltiRefundResponse response =
                    khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 5000L);

            assertThat(response.isRefundSuccessful()).isTrue();

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            assertThat(body).contains("\"amount\"").contains("5000");
            assertThat(body).doesNotContain("pidx");
        }

        @Test
        @DisplayName("partial refund: correct path")
        void refundPayment_partialRefund_correctPath() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"transaction_id":"TXN-001","refunded":true,"status":"Refunded"}
                            """));

            khaltiClient.refundPayment("TXN-001", 5000L);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getPath())
                    .isEqualTo("/api/merchant-transaction/TXN-001/refund/");
        }

        @Test
        @DisplayName("throws KhaltiException on 400")
        void refundPayment_badRequest_throwsKhaltiException() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"detail": "Cannot refund this transaction"}
                            """));

            assertThatThrownBy(() -> khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti refund failed")
                    .hasMessageContaining("Only Completed payments can be refunded")
                    .extracting("httpStatus").isEqualTo(400);
        }

        @Test
        @DisplayName("throws KhaltiException on 500 server error")
        void refundPayment_serverError_throwsKhaltiException() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("Khalti server error during refund");
        }

        @Test
        @DisplayName("throws KhaltiException when transactionId is null")
        void refundPayment_nullTransactionId_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.refundPayment(null))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("transactionId cannot be null or blank")
                    .hasMessageContaining("lookupPayment()");
        }

        @Test
        @DisplayName("throws KhaltiException when transactionId is blank")
        void refundPayment_blankTransactionId_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.refundPayment("   "))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("transactionId cannot be null or blank");
        }

        @Test
        @DisplayName("throws KhaltiException when transactionId is null — partial overload")
        void refundPayment_partial_nullTransactionId_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.refundPayment(null, 5000L))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("transactionId cannot be null or blank");
        }

        @Test
        @DisplayName("throws KhaltiException when amountPaisa is zero")
        void refundPayment_zeroAmountPaisa_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 0L))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("amountPaisa must be greater than 0")
                    .hasMessageContaining("full refund");
        }

        @Test
        @DisplayName("throws KhaltiException when amountPaisa is negative")
        void refundPayment_negativeAmountPaisa_throwsKhaltiException() {
            assertThatThrownBy(() -> khaltiClient.refundPayment("GFq9DrfGSZQKjsj", -100L))
                    .isInstanceOf(KhaltiException.class)
                    .hasMessageContaining("amountPaisa must be greater than 0");
        }

        @Test
        @DisplayName("isRefundSuccessful() = false when refunded=false in response")
        void refundPayment_refundedFalseInResponse_isRefundSuccessfulFalse() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":false,"status":"Pending"}
                            """));

            KhaltiRefundResponse response = khaltiClient.refundPayment("GFq9DrfGSZQKjsj");
            assertThat(response.isRefundSuccessful()).isFalse();
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.PENDING);
        }

        @Test
        @DisplayName("paymentStatus() returns REFUNDED for successful refund")
        void refundPayment_paymentStatus_returnsRefunded() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":true,"status":"Refunded"}
                            """));

            KhaltiRefundResponse response = khaltiClient.refundPayment("GFq9DrfGSZQKjsj");
            assertThat(response.paymentStatus()).isEqualTo(KhaltiPaymentStatus.REFUNDED);
        }
    }

    // ── Retry behavior ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("initiatePayment: retries on 5xx and succeeds on second attempt")
        void initiatePayment_retryOnce_succeeds() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","payment_url":"https://pay.khalti.com/?pidx=bZQLD9","expires_in":900}
                            """));

            KhaltiInitiateResponse response = retryClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test Product")
                            .returnUrl("https://example.com/callback").build());

            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);

            mockWebServer.takeRequest();
            RecordedRequest successReq = mockWebServer.takeRequest();
            assertThat(successReq.getPath()).isEqualTo("/epayment/initiate/");
        }

        @Test
        @DisplayName("initiatePayment: retries twice and succeeds on third attempt")
        void initiatePayment_retryTwice_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","payment_url":"https://pay.khalti.com","expires_in":900}
                            """));

            KhaltiInitiateResponse response = retryClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test").returnUrl("https://example.com/cb").build());

            assertThat(response.pidx()).isEqualTo(FAKE_PIDX);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("initiatePayment: exhausts all retries and throws")
        void initiatePayment_exhaustsRetries_throws() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> retryClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test").returnUrl("https://example.com/cb").build()))
                    .isInstanceOf(KhaltiException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("initiatePayment: does NOT retry on 401 — only 1 request")
        void initiatePayment_doesNotRetry_on4xx() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401).setBody("{\"detail\": \"Invalid key\"}"));

            assertThatThrownBy(() -> retryClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(AMOUNT_PAISA).purchaseOrderId(FAKE_ORDER_ID)
                            .purchaseOrderName("Test").returnUrl("https://example.com/cb").build()))
                    .isInstanceOf(KhaltiException.class);

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("lookupPayment: retries on 5xx and succeeds")
        void lookupPayment_retryOnce_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","status":"Completed","total_amount":10000}
                            """));

            KhaltiLookupResponse response = retryClient.lookupPayment(FAKE_PIDX);
            assertThat(response.isPaymentSuccessful()).isTrue();
            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("lookupPayment: exhausts retries and throws")
        void lookupPayment_exhaustsRetries_throws() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> retryClient.lookupPayment(FAKE_PIDX))
                    .isInstanceOf(KhaltiException.class);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("lookupPayment: does NOT retry on 4xx")
        void lookupPayment_doesNotRetry_on4xx() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400).setBody("{\"pidx\":[\"blank\"]}"));

            assertThatThrownBy(() -> retryClient.lookupPayment(FAKE_PIDX))
                    .isInstanceOf(KhaltiException.class);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("refundPayment: retries on 5xx and succeeds")
        void refundPayment_retryOnce_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":true,"status":"Refunded"}
                            """));

            KhaltiRefundResponse response = retryClient.refundPayment("GFq9DrfGSZQKjsj");
            assertThat(response.isRefundSuccessful()).isTrue();
            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("refundPayment: does NOT retry on 4xx")
        void refundPayment_doesNotRetry_on4xx() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400).setBody("{\"detail\": \"Cannot refund\"}"));

            assertThatThrownBy(() -> retryClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .isInstanceOf(KhaltiException.class);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry disabled by default — only 1 request on 5xx")
        void retryDisabledByDefault_onlyOneRequest() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> khaltiClient.lookupPayment(FAKE_PIDX))
                    .isInstanceOf(KhaltiException.class);
            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ── Utility methods ───────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_returnsTrueWhenSandboxEnabled() {
        assertThat(khaltiClient.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("baseUrl() returns non-blank value")
    void baseUrl_returnsNonBlankValue() {
        assertThat(khaltiClient.baseUrl()).isNotBlank();
    }

    @Test
    @DisplayName("baseDomain() returns non-blank value")
    void baseDomain_returnsNonBlankValue() {
        assertThat(khaltiClient.baseDomain()).isNotBlank();
    }

    @Test
    @DisplayName("baseDomain() does not end with slash")
    void baseDomain_doesNotEndWithSlash() {
        assertThat(khaltiClient.baseDomain()).doesNotEndWith("/");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private NepalPayProperties.KhaltiProperties buildKhaltiProperties(RetryProperties retry) {
        return new NepalPayProperties.KhaltiProperties(
                FAKE_SECRET_KEY,
                "https://example.com/callback",
                "https://example.com",
                true,
                10,
                retry
        );
    }
}