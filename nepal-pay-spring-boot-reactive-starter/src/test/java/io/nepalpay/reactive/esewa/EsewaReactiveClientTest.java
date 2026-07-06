package io.nepalpay.reactive.esewa;

import io.nepalpay.core.esewa.model.EsewaFormPayload;
import io.nepalpay.core.esewa.model.EsewaStatusResponse;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.reactive.config.NepalPayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EsewaReactiveClient}.
 */
@DisplayName("EsewaReactiveClient")
class EsewaReactiveClientTest {

    private static final String SANDBOX_SECRET_KEY   = "8gBm/:&EnhH.1/q";
    private static final String SANDBOX_PRODUCT_CODE = "EPAYTEST";
    private static final String SUCCESS_URL          =
            "https://example.com/esewa/success";
    private static final String FAILURE_URL          =
            "https://example.com/esewa/failure";
    private static final String TEST_UUID            =
            "test-transaction-uuid-001";

    private MockWebServer        mockWebServer;
    private EsewaReactiveClient  esewaClient;
    private EsewaReactiveClient  retryEsewaClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String mockUrl = mockWebServer.url("/").toString();

        esewaClient = new EsewaReactiveClient(
                buildProps(null), WebClient.builder(), mockUrl);

        RetryProperties fastRetry =
                new RetryProperties(true, 2, 0L, 1.0, 0L);
        retryEsewaClient = new EsewaReactiveClient(
                buildProps(fastRetry), WebClient.builder(), mockUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── buildFormPayload() ────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFormPayload()")
    class BuildFormPayload {

        @Test
        @DisplayName("success: all fields populated correctly")
        void buildFormPayload_success() {
            // Synchronous — no StepVerifier needed
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    new BigDecimal("100.00"), TEST_UUID);

            assertThat(payload.amount()).isEqualTo("100.00");
            assertThat(payload.taxAmount()).isEqualTo("0.00");
            assertThat(payload.totalAmount()).isEqualTo("100.00");
            assertThat(payload.transactionUuid()).isEqualTo(TEST_UUID);
            assertThat(payload.productCode()).isEqualTo(SANDBOX_PRODUCT_CODE);
            assertThat(payload.successUrl()).isEqualTo(SUCCESS_URL);
            assertThat(payload.failureUrl()).isEqualTo(FAILURE_URL);
            assertThat(payload.signedFieldNames())
                    .isEqualTo("total_amount,transaction_uuid,product_code");
            assertThat(payload.signature()).isNotBlank();
            assertThat(payload.formActionUrl())
                    .contains("rc-epay.esewa.com.np");
        }

        @Test
        @DisplayName("HMAC signature is correct")
        void buildFormPayload_hmacSignature_isCorrect() throws Exception {
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    new BigDecimal("100.00"), TEST_UUID);

            String message = "total_amount=100.00"
                    + ",transaction_uuid=" + TEST_UUID
                    + ",product_code=" + SANDBOX_PRODUCT_CODE;
            String expected = computeHmac(message, SANDBOX_SECRET_KEY);

            assertThat(payload.signature()).isEqualTo(expected);
        }

        @Test
        @DisplayName("totalAmount includes all charges")
        void buildFormPayload_totalAmount_includesCharges() {
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    TEST_UUID,
                    new BigDecimal("5.00"),
                    new BigDecimal("15.00"));

            assertThat(payload.totalAmount()).isEqualTo("130.00");
        }

        @Test
        @DisplayName("throws EsewaException when amount is zero")
        void buildFormPayload_zeroAmount_throws() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            esewaClient.buildFormPayload(BigDecimal.ZERO, TEST_UUID))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws EsewaException when uuid is blank")
        void buildFormPayload_blankUuid_throws() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            esewaClient.buildFormPayload(new BigDecimal("100"), "  "))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("transactionUuid is required");
        }
    }

    // ── checkStatus() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkStatus()")
    class CheckStatus {

        @Test
        @DisplayName("COMPLETE → isPaymentSuccessful() = true")
        void checkStatus_complete_isSuccessful() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "product_code": "EPAYTEST",
                                "transaction_uuid": "test-transaction-uuid-001",
                                "total_amount": 100.0,
                                "status": "COMPLETE",
                                "ref_id": "0001TS9"
                            }
                            """));

            StepVerifier.create(
                            esewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectNextMatches(res ->
                            res.isPaymentSuccessful()
                                    && res.status().equals("COMPLETE")
                                    && res.refId().equals("0001TS9"))
                    .verifyComplete();

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath())
                    .contains("product_code=EPAYTEST")
                    .contains("transaction_uuid=" + TEST_UUID)
                    .contains("total_amount=100.00");
        }

        @Test
        @DisplayName("INCOMPLETE → isPaymentSuccessful() = false")
        void checkStatus_incomplete_notSuccessful() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"status":"INCOMPLETE","transaction_uuid":"test-transaction-uuid-001"}
                            """));

            StepVerifier.create(
                            esewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectNextMatches(res -> !res.isPaymentSuccessful())
                    .verifyComplete();
        }

        @Test
        @DisplayName("emits EsewaException on 400")
        void checkStatus_400_emitsError() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"error": "Invalid product_code"}
                            """));

            StepVerifier.create(
                            esewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectErrorMatches(err ->
                            err instanceof EsewaException ee
                                    && ee.httpStatus() == 400
                                    && err.getMessage().contains(
                                    "eSewa status check failed"))
                    .verify();
        }

        @Test
        @DisplayName("emits EsewaException when uuid is blank")
        void checkStatus_blankUuid_emitsError() {
            StepVerifier.create(esewaClient.checkStatus("  ", "100.00"))
                    .expectErrorMatches(err ->
                            err instanceof EsewaException
                                    && err.getMessage().contains(
                                    "transactionUuid cannot be null or blank"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("emits EsewaException when totalAmount is blank")
        void checkStatus_blankAmount_emitsError() {
            StepVerifier.create(
                            esewaClient.checkStatus(TEST_UUID, "  "))
                    .expectErrorMatches(err ->
                            err instanceof EsewaException
                                    && err.getMessage().contains(
                                    "totalAmount cannot be null or blank"))
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
        }
    }

    // ── verifyCallback() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyCallback()")
    class VerifyCallback {

        @Test
        @DisplayName("emits EsewaException when data is null")
        void verifyCallback_null_emitsError() {
            StepVerifier.create(esewaClient.verifyCallback(null))
                    .expectErrorMatches(err ->
                            err instanceof EsewaException
                                    && err.getMessage().contains(
                                    "eSewa callback data cannot be null or blank"))
                    .verify();
        }

        @Test
        @DisplayName("emits EsewaException when data is invalid Base64")
        void verifyCallback_invalidBase64_emitsError() {
            StepVerifier.create(esewaClient.verifyCallback("NOT_BASE64!!"))
                    .expectError(EsewaException.class)
                    .verify();
        }

        @Test
        @DisplayName("valid callback with correct signature → verified=true")
        void verifyCallback_validSignedCallback_verified() throws Exception {
            String transactionCode  = "000ABCD";
            String status           = "COMPLETE";
            String totalAmount      = "100.00";
            String signedFieldNames =
                    "transaction_code,status,total_amount," +
                            "transaction_uuid,product_code,signed_field_names";

            String message = "transaction_code=" + transactionCode
                    + ",status=" + status
                    + ",total_amount=" + totalAmount
                    + ",transaction_uuid=" + TEST_UUID
                    + ",product_code=" + SANDBOX_PRODUCT_CODE
                    + ",signed_field_names=" + signedFieldNames;

            String realSignature = computeHmac(message, SANDBOX_SECRET_KEY);

            String callbackJson = """
                    {
                        "transaction_code": "%s",
                        "status": "%s",
                        "total_amount": "%s",
                        "transaction_uuid": "%s",
                        "product_code": "%s",
                        "signed_field_names": "%s",
                        "signature": "%s"
                    }
                    """.formatted(transactionCode, status, totalAmount,
                    TEST_UUID, SANDBOX_PRODUCT_CODE, signedFieldNames,
                    realSignature);

            String encodedData = Base64.getEncoder()
                    .encodeToString(callbackJson.getBytes(
                            StandardCharsets.UTF_8));

            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"product_code":"EPAYTEST","transaction_uuid":"%s","total_amount":100.0,"status":"COMPLETE","ref_id":"0001TS9"}
                            """.formatted(TEST_UUID)));

            StepVerifier.create(esewaClient.verifyCallback(encodedData))
                    .expectNextMatches(result ->
                            result.isPaymentSuccessful()
                                    && result.verified()
                                    && result.callbackData()
                                    .transactionUuid().equals(TEST_UUID)
                                    && result.statusResponse()
                                    .refId().equals("0001TS9"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("tampered signature emits EsewaException")
        void verifyCallback_tamperedSignature_emitsError() {
            String callbackJson = """
                    {
                        "transaction_code": "000ABCD",
                        "status": "COMPLETE",
                        "total_amount": "100.00",
                        "transaction_uuid": "%s",
                        "product_code": "%s",
                        "signed_field_names": "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names",
                        "signature": "TAMPERED_SIGNATURE=="
                    }
                    """.formatted(TEST_UUID, SANDBOX_PRODUCT_CODE);

            String encodedData = Base64.getEncoder()
                    .encodeToString(callbackJson.getBytes(
                            StandardCharsets.UTF_8));

            StepVerifier.create(esewaClient.verifyCallback(encodedData))
                    .expectErrorMatches(err ->
                            err instanceof EsewaException
                                    && err.getMessage().contains(
                                    "signature verification FAILED"))
                    .verify();
        }
    }

    // ── Retry behavior ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry behavior")
    class RetryBehavior {

        @Test
        @DisplayName("checkStatus: retries on 5xx and succeeds")
        void checkStatus_retryOnce_succeeds() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"status":"COMPLETE","transaction_uuid":"test-transaction-uuid-001"}
                            """));

            StepVerifier.create(
                            retryEsewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectNextMatches(EsewaStatusResponse::isPaymentSuccessful)
                    .verifyComplete();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("checkStatus: exhausts retries and emits error")
        void checkStatus_exhaustsRetries_emitsError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            retryEsewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectError(EsewaException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("checkStatus: does NOT retry on 400")
        void checkStatus_doesNotRetry_on400() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("{\"error\": \"Invalid product_code\"}"));

            StepVerifier.create(
                            retryEsewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectError(EsewaException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry disabled by default — 1 request on 5xx")
        void retryDisabled_onlyOneRequest() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(
                            esewaClient.checkStatus(TEST_UUID, "100.00"))
                    .expectError(EsewaException.class)
                    .verify();

            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTransactionUuid: returns unique non-blank strings")
    void generateTransactionUuid_uniqueNonBlank() {
        String u1 = EsewaReactiveClient.generateTransactionUuid();
        String u2 = EsewaReactiveClient.generateTransactionUuid();
        assertThat(u1).isNotBlank();
        assertThat(u2).isNotBlank();
        assertThat(u1).isNotEqualTo(u2);
    }

    @Test
    @DisplayName("isSandbox() returns true")
    void isSandbox_true() {
        assertThat(esewaClient.isSandbox()).isTrue();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private NepalPayProperties.EsewaProperties buildProps(
            RetryProperties retry) {
        return new NepalPayProperties.EsewaProperties(
                SANDBOX_SECRET_KEY, SANDBOX_PRODUCT_CODE,
                SUCCESS_URL, FAILURE_URL, true, 10, retry);
    }

    private String computeHmac(String message, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}