package io.nepalpay.reactive.khalti;

import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiPaymentStatus;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.reactive.config.NepalPayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KhaltiReactiveClient}.
 *
 * <p>Uses {@link StepVerifier} (reactor-test) to assert on Mono responses.
 * Uses {@link MockWebServer} (OkHttp) to mock HTTP responses —
 * same approach as blocking tests, WebClient calls the mock server.
 *
 * <p>Key pattern:
 * <pre>{@code
 * StepVerifier.create(khaltiReactiveClient.initiatePayment(request))
 *     .expectNextMatches(res -> res.pidx().equals("bZQLD9"))
 *     .verifyComplete();
 * }</pre>
 */
@DisplayName("KhaltiReactiveClient")
class KhaltiReactiveClientTest {

    private static final String FAKE_SECRET_KEY = "test_secret_key_abc123";
    private static final String FAKE_PIDX       = "bZQLD9wRVWo4CdESSfuSsB";
    private static final String FAKE_ORDER_ID   = "ORD-TEST-001";
    private static final long   AMOUNT_PAISA    = 10000L;

    private MockWebServer          mockWebServer;
    private KhaltiReactiveClient   khaltiClient;
    private KhaltiReactiveClient   retryClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String mockUrl = mockWebServer.url("/").toString();

        khaltiClient = new KhaltiReactiveClient(
                buildProps(null), WebClient.builder(), mockUrl);

        RetryProperties fastRetry =
                new RetryProperties(true, 2, 0L, 1.0, 0L);
        retryClient = new KhaltiReactiveClient(
                buildProps(fastRetry), WebClient.builder(), mockUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── initiatePayment() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("initiatePayment()")
    class InitiatePayment {

        @Test
        @DisplayName("success: emits pidx and paymentUrl")
        void initiatePayment_success() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "pidx": "bZQLD9wRVWo4CdESSfuSsB",
                                "payment_url": "https://pay.khalti.com/?pidx=bZQLD9",
                                "expires_at": "2024-01-01T00:15:00Z",
                                "expires_in": 900
                            }
                            """));

            StepVerifier.create(
                            khaltiClient.initiatePayment(buildInitiateRequest()))
                    .expectNextMatches(res ->
                            res.pidx().equals(FAKE_PIDX)
                                    && res.paymentUrl().contains("pay.khalti.com")
                                    && res.expiresIn().equals(900))
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/epayment/initiate/");
            assertThat(req.getHeader("Authorization"))
                    .isEqualTo("Key " + FAKE_SECRET_KEY);
            assertThat(req.getBody().readUtf8())
                    .contains(FAKE_ORDER_ID)
                    .contains(String.valueOf(AMOUNT_PAISA));
        }

        @Test
        @DisplayName("emits KhaltiException on 401 unauthorized")
        void initiatePayment_401_emitsError() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"detail": "Authentication credentials not provided."}
                            """));

            StepVerifier.create(
                            khaltiClient.initiatePayment(buildInitiateRequest()))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException ke
                                    && ke.httpStatus() == 401
                                    && ke.getMessage().contains(
                                    "Khalti payment initiation failed"))
                    .verify();
        }

        @Test
        @DisplayName("emits KhaltiException on 500 server error")
        void initiatePayment_500_emitsError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            khaltiClient.initiatePayment(buildInitiateRequest()))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException ke
                                    && ke.httpStatus() == 500
                                    && ke.getMessage().contains("Khalti server error"))
                    .verify();
        }

        @Test
        @DisplayName("emits KhaltiException when amount is zero — sync validation")
        void initiatePayment_zeroAmount_emitsError() {
            // Validation is synchronous — no HTTP call made
            StepVerifier.create(
                            khaltiClient.initiatePayment(
                                    KhaltiInitiateRequest.builder()
                                            .amount(0L)
                                            .purchaseOrderId(FAKE_ORDER_ID)
                                            .purchaseOrderName("Test")
                                            .returnUrl("https://example.com/cb")
                                            .build()))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains(
                                    "Amount must be greater than 0"))
                    .verify();

            // No HTTP request made — validation rejected it
            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits KhaltiException when pidx is blank")
        void initiatePayment_blankOrderId_emitsError() {
            StepVerifier.create(
                            khaltiClient.initiatePayment(
                                    KhaltiInitiateRequest.builder()
                                            .amount(AMOUNT_PAISA)
                                            .purchaseOrderId("  ")
                                            .purchaseOrderName("Test")
                                            .returnUrl("https://example.com/cb")
                                            .build()))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains(
                                    "purchaseOrderId is required"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits KhaltiException when no returnUrl anywhere")
        void initiatePayment_noReturnUrl_emitsError() {
            NepalPayProperties.KhaltiProperties noUrlProps =
                    new NepalPayProperties.KhaltiProperties(
                            FAKE_SECRET_KEY, null, null, true, 10, null);

            KhaltiReactiveClient clientNoUrl = new KhaltiReactiveClient(
                    noUrlProps,
                    WebClient.builder(),
                    mockWebServer.url("/").toString());

            StepVerifier.create(
                            clientNoUrl.initiatePayment(
                                    KhaltiInitiateRequest.builder()
                                            .amount(AMOUNT_PAISA)
                                            .purchaseOrderId(FAKE_ORDER_ID)
                                            .purchaseOrderName("Test")
                                            .build()))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains("returnUrl is required"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }
    }

    // ── lookupPayment() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("lookupPayment()")
    class LookupPayment {

        @Test
        @DisplayName("Completed → emits successful response")
        void lookupPayment_completed_success() throws InterruptedException {
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
                                "fee": 0,
                                "refunded": false
                            }
                            """));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectNextMatches(res ->
                            res.pidx().equals(FAKE_PIDX)
                                    && res.isPaymentSuccessful()
                                    && res.totalAmount().equals(10000L)
                                    && res.paymentStatus() == KhaltiPaymentStatus.COMPLETED)
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/epayment/lookup/");
            assertThat(req.getBody().readUtf8()).contains(FAKE_PIDX);
        }

        @Test
        @DisplayName("Pending → emits non-successful response")
        void lookupPayment_pending_notSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","status":"Pending","total_amount":10000}
                            """));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectNextMatches(res ->
                            !res.isPaymentSuccessful()
                                    && res.paymentStatus() == KhaltiPaymentStatus.PENDING)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Refunded → isRefunded() = true")
        void lookupPayment_refunded_isRefunded() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","status":"Refunded",
                             "total_amount":10000,"refunded":true}
                            """));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectNextMatches(res ->
                            res.isRefunded()
                                    && !res.isPaymentSuccessful()
                                    && res.paymentStatus() == KhaltiPaymentStatus.REFUNDED)
                    .verifyComplete();
        }

        @Test
        @DisplayName("isAmountValid: matches expected amount")
        void lookupPayment_amountValid() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","status":"Completed","total_amount":10000}
                            """));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectNextMatches(res ->
                            res.isAmountValid(10000L)
                                    && !res.isAmountValid(1L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("emits KhaltiException when pidx is null")
        void lookupPayment_nullPidx_emitsError() {
            StepVerifier.create(khaltiClient.lookupPayment(null))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains(
                                    "pidx cannot be null or blank"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits KhaltiException on 400")
        void lookupPayment_400_emitsError() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx": ["This field may not be blank."]}
                            """));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException ke
                                    && ke.httpStatus() == 400
                                    && ke.getMessage().contains("Khalti lookup failed"))
                    .verify();
        }
    }

    // ── refundPayment() ───────────────────────────────────────────────────

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

            StepVerifier.create(
                            khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .expectNextMatches(res ->
                            res.isRefundSuccessful()
                                    && res.paymentStatus() == KhaltiPaymentStatus.REFUNDED
                                    && res.transactionId().equals("GFq9DrfGSZQKjsj"))
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath())
                    .isEqualTo("/api/merchant-transaction/GFq9DrfGSZQKjsj/refund/");
            assertThat(req.getBody().readUtf8()).isEqualTo("{}");
        }

        @Test
        @DisplayName("partial refund: sends amount in body")
        void refundPayment_partialRefund_sendsAmount()
                throws InterruptedException {

            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9DrfGSZQKjsj","refunded":true,"status":"Refunded"}
                            """));

            StepVerifier.create(
                            khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 5000L))
                    .expectNextMatches(KhaltiRefundResponse::isRefundSuccessful)
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            String body = req.getBody().readUtf8();
            assertThat(body).contains("\"amount\"").contains("5000");
        }

        @Test
        @DisplayName("full refund: correct path")
        void refundPayment_correctPath() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"transaction_id":"TXN-001","refunded":true,"status":"Refunded"}
                            """));

            StepVerifier.create(
                            khaltiClient.refundPayment("TXN-001"))
                    .expectNextCount(1)
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getPath())
                    .isEqualTo("/api/merchant-transaction/TXN-001/refund/");
        }

        @Test
        @DisplayName("emits KhaltiException on 400")
        void refundPayment_400_emitsError() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"detail": "Cannot refund this transaction"}
                            """));

            StepVerifier.create(
                            khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException ke
                                    && ke.httpStatus() == 400
                                    && ke.getMessage().contains("Khalti refund failed"))
                    .verify();
        }

        @Test
        @DisplayName("emits KhaltiException when transactionId is null")
        void refundPayment_nullTransactionId_emitsError() {
            StepVerifier.create(khaltiClient.refundPayment(null))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains(
                                    "transactionId cannot be null or blank"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits KhaltiException when amountPaisa is zero")
        void refundPayment_zeroAmount_emitsError() {
            StepVerifier.create(
                            khaltiClient.refundPayment("GFq9DrfGSZQKjsj", 0L))
                    .expectErrorMatches(err ->
                            err instanceof KhaltiException
                                    && err.getMessage().contains(
                                    "amountPaisa must be greater than 0"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("isRefundSuccessful() = false when refunded=false in response")
        void refundPayment_refundedFalse_isNotSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9","transaction_id":"GFq9","refunded":false,"status":"Pending"}
                            """));

            StepVerifier.create(
                            khaltiClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .expectNextMatches(res -> !res.isRefundSuccessful())
                    .verifyComplete();
        }
    }

    // ── Retry behavior ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("initiatePayment: retries on 5xx and succeeds")
        void initiatePayment_retryOnce_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","payment_url":"https://pay.khalti.com","expires_in":900}
                            """));

            StepVerifier.create(
                            retryClient.initiatePayment(buildInitiateRequest()))
                    .expectNextMatches(res -> res.pidx().equals(FAKE_PIDX))
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("initiatePayment: retries twice and succeeds")
        void initiatePayment_retryTwice_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"pidx":"bZQLD9wRVWo4CdESSfuSsB","payment_url":"https://pay.khalti.com","expires_in":900}
                            """));

            StepVerifier.create(
                            retryClient.initiatePayment(buildInitiateRequest()))
                    .expectNextMatches(res -> res.pidx().equals(FAKE_PIDX))
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("initiatePayment: exhausts retries and emits error")
        void initiatePayment_exhaustsRetries_emitsError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            retryClient.initiatePayment(buildInitiateRequest()))
                    .expectError(KhaltiException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("initiatePayment: does NOT retry on 401")
        void initiatePayment_doesNotRetry_on401() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setBody("{\"detail\": \"Invalid key\"}"));

            StepVerifier.create(
                            retryClient.initiatePayment(buildInitiateRequest()))
                    .expectError(KhaltiException.class)
                    .verify();

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

            StepVerifier.create(retryClient.lookupPayment(FAKE_PIDX))
                    .expectNextMatches(KhaltiLookupResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("lookupPayment: does NOT retry on 400")
        void lookupPayment_doesNotRetry_on400() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("{\"pidx\":[\"blank\"]}"));

            StepVerifier.create(retryClient.lookupPayment(FAKE_PIDX))
                    .expectError(KhaltiException.class)
                    .verify();

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

            StepVerifier.create(
                            retryClient.refundPayment("GFq9DrfGSZQKjsj"))
                    .expectNextMatches(KhaltiRefundResponse::isRefundSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("retry disabled by default — 1 request on 5xx")
        void retryDisabled_onlyOneRequest() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(khaltiClient.lookupPayment(FAKE_PIDX))
                    .expectError(KhaltiException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_trueByDefault() {
        assertThat(khaltiClient.isSandbox()).isTrue();
    }

    @Test
    @DisplayName("baseUrl() returns mock URL")
    void baseUrl_returnsNonBlank() {
        assertThat(khaltiClient.baseUrl()).isNotBlank();
    }

    @Test
    @DisplayName("baseDomain() does not end with slash")
    void baseDomain_noTrailingSlash() {
        assertThat(khaltiClient.baseDomain()).doesNotEndWith("/");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private KhaltiInitiateRequest buildInitiateRequest() {
        return KhaltiInitiateRequest.builder()
                .amount(AMOUNT_PAISA)
                .purchaseOrderId(FAKE_ORDER_ID)
                .purchaseOrderName("Test Product")
                .returnUrl("https://example.com/callback")
                .websiteUrl("https://example.com")
                .build();
    }

    private NepalPayProperties.KhaltiProperties buildProps(
            RetryProperties retry) {
        return new NepalPayProperties.KhaltiProperties(
                FAKE_SECRET_KEY,
                "https://example.com/callback",
                "https://example.com",
                true, 10, retry);
    }
}