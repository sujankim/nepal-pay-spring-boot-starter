package io.nepalpay.esewa;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.esewa.exception.EsewaException;
import io.nepalpay.esewa.model.EsewaFormPayload;
import io.nepalpay.esewa.model.EsewaStatusResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for EsewaClient.
 *
 * Tests cover:
 * - HMAC-SHA256 signature generation correctness
 * - Form payload field values
 * - Base64 callback decode and signature verification
 * - Status API HTTP call via MockWebServer
 * - Error handling for invalid inputs
 */
@DisplayName("EsewaClient")
class EsewaClientTest {

    // ── Test fixtures ─────────────────────────────────────────────────────────

    // Official eSewa sandbox credentials from developer.esewa.com.np
    private static final String SANDBOX_SECRET_KEY  = "8gBm/:&EnhH.1/q";
    private static final String SANDBOX_PRODUCT_CODE = "EPAYTEST";
    private static final String SUCCESS_URL = "https://example.com/esewa/success";
    private static final String FAILURE_URL = "https://example.com/esewa/failure";
    private static final String TEST_UUID   = "test-transaction-uuid-001";

    private MockWebServer mockWebServer;
    private EsewaClient   esewaClient;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String mockServerUrl = mockWebServer.url("/").toString();

        NepalPayProperties.EsewaProperties props = buildEsewaProperties();

        // Package-private test constructor — injects MockWebServer URL
        esewaClient = new EsewaClient(props, RestClient.builder(), mockServerUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── buildFormPayload() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFormPayload()")
    class BuildFormPayload {

        @Test
        @DisplayName("success: all fields populated correctly")
        void buildFormPayload_success_allFieldsPopulated() {
            BigDecimal amount = new BigDecimal("100.00");

            EsewaFormPayload payload = esewaClient.buildFormPayload(amount, TEST_UUID);

            // Assert all fields are present and correct
            assertThat(payload).isNotNull();
            assertThat(payload.amount()).isEqualTo("100.00");
            assertThat(payload.taxAmount()).isEqualTo("0.00");
            assertThat(payload.totalAmount()).isEqualTo("100.00");
            assertThat(payload.transactionUuid()).isEqualTo(TEST_UUID);
            assertThat(payload.productCode()).isEqualTo(SANDBOX_PRODUCT_CODE);
            assertThat(payload.productServiceCharge()).isEqualTo("0.00");
            assertThat(payload.productDeliveryCharge()).isEqualTo("0.00");
            assertThat(payload.successUrl()).isEqualTo(SUCCESS_URL);
            assertThat(payload.failureUrl()).isEqualTo(FAILURE_URL);
            assertThat(payload.signedFieldNames())
                    .isEqualTo("total_amount,transaction_uuid,product_code");
            assertThat(payload.signature()).isNotBlank();
            assertThat(payload.formActionUrl()).contains("rc-epay.esewa.com.np");
        }

        @Test
        @DisplayName("totalAmount: includes tax + service + delivery correctly")
        void buildFormPayload_totalAmount_includesAllCharges() {
            BigDecimal amount   = new BigDecimal("100.00");
            BigDecimal tax      = new BigDecimal("10.00");
            BigDecimal service  = new BigDecimal("5.00");
            BigDecimal delivery = new BigDecimal("15.00");

            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    amount, tax, TEST_UUID, service, delivery);

            // total = 100 + 10 + 5 + 15 = 130.00
            assertThat(payload.totalAmount()).isEqualTo("130.00");
            assertThat(payload.amount()).isEqualTo("100.00");
            assertThat(payload.taxAmount()).isEqualTo("10.00");
            assertThat(payload.productServiceCharge()).isEqualTo("5.00");
            assertThat(payload.productDeliveryCharge()).isEqualTo("15.00");
        }

        @Test
        @DisplayName("HMAC signature is correct — verified against known input")
        void buildFormPayload_hmacSignature_isCorrect() throws Exception {
            BigDecimal amount = new BigDecimal("100.00");

            EsewaFormPayload payload = esewaClient.buildFormPayload(amount, TEST_UUID);

            // Manually compute expected signature using official eSewa formula
            // Message: "total_amount=100.00,transaction_uuid=test-uuid,product_code=EPAYTEST"
            String message = "total_amount=100.00"
                    + ",transaction_uuid=" + TEST_UUID
                    + ",product_code=" + SANDBOX_PRODUCT_CODE;

            String expectedSignature = computeHmacSha256(message, SANDBOX_SECRET_KEY);

            assertThat(payload.signature()).isEqualTo(expectedSignature);
        }

        @Test
        @DisplayName("sandbox mode: formActionUrl points to sandbox")
        void buildFormPayload_sandbox_formActionUrlIsSandbox() {
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    new BigDecimal("100"), TEST_UUID);

            assertThat(payload.formActionUrl())
                    .isEqualTo("https://rc-epay.esewa.com.np/api/epay/main/v2/form");
        }

        @Test
        @DisplayName("throws EsewaException when amount is zero")
        void buildFormPayload_zeroAmount_throwsEsewaException() {
            assertThatThrownBy(() ->
                    esewaClient.buildFormPayload(BigDecimal.ZERO, TEST_UUID))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws EsewaException when amount is negative")
        void buildFormPayload_negativeAmount_throwsEsewaException() {
            assertThatThrownBy(() ->
                    esewaClient.buildFormPayload(new BigDecimal("-50"), TEST_UUID))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws EsewaException when transactionUuid is blank")
        void buildFormPayload_blankUuid_throwsEsewaException() {
            assertThatThrownBy(() ->
                    esewaClient.buildFormPayload(new BigDecimal("100"), "  "))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("transactionUuid is required");
        }

        @Test
        @DisplayName("throws EsewaException when transactionUuid is null")
        void buildFormPayload_nullUuid_throwsEsewaException() {
            assertThatThrownBy(() ->
                    esewaClient.buildFormPayload(new BigDecimal("100"), null))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("transactionUuid is required");
        }
    }

    // ── checkStatus() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkStatus()")
    class CheckStatus {

        @Test
        @DisplayName("success: COMPLETE status → isPaymentSuccessful() = true")
        void checkStatus_complete_isSuccessful() throws InterruptedException {
            // Arrange — mock eSewa status API response
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

            // Act
            EsewaStatusResponse response = esewaClient.checkStatus(TEST_UUID, "100.00");

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo("COMPLETE");
            assertThat(response.transactionUuid()).isEqualTo(TEST_UUID);
            assertThat(response.productCode()).isEqualTo(SANDBOX_PRODUCT_CODE);
            assertThat(response.refId()).isEqualTo("0001TS9");
            assertThat(response.isPaymentSuccessful()).isTrue();

            // Assert — correct query params were sent
            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getPath())
                    .contains("product_code=EPAYTEST")
                    .contains("total_amount=100.00")
                    .contains("transaction_uuid=" + TEST_UUID);
        }

        @Test
        @DisplayName("INCOMPLETE status → isPaymentSuccessful() = false")
        void checkStatus_incomplete_isNotSuccessful() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "product_code": "EPAYTEST",
                                "transaction_uuid": "test-transaction-uuid-001",
                                "total_amount": 100.0,
                                "status": "INCOMPLETE",
                                "ref_id": null
                            }
                            """));

            // Act
            EsewaStatusResponse response = esewaClient.checkStatus(TEST_UUID, "100.00");

            // Assert
            assertThat(response.isPaymentSuccessful()).isFalse();
        }

        @Test
        @DisplayName("throws EsewaException on 400 bad request")
        void checkStatus_badRequest_throwsEsewaException() {
            // Arrange
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "error": "Invalid product_code"
                            }
                            """));

            // Act + Assert
            assertThatThrownBy(() -> esewaClient.checkStatus(TEST_UUID, "100.00"))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("eSewa status check failed")
                    .extracting("httpStatus")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("throws EsewaException when transactionUuid is blank")
        void checkStatus_blankUuid_throwsEsewaException() {
            assertThatThrownBy(() -> esewaClient.checkStatus("  ", "100.00"))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("transactionUuid cannot be null or blank");
        }

        @Test
        @DisplayName("throws EsewaException when totalAmount is blank")
        void checkStatus_blankAmount_throwsEsewaException() {
            assertThatThrownBy(() -> esewaClient.checkStatus(TEST_UUID, "  "))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("totalAmount cannot be null or blank");
        }
    }

    // ── verifyCallback() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyCallback()")
    class VerifyCallback {

        @Test
        @DisplayName("throws EsewaException when encodedData is null")
        void verifyCallback_nullData_throwsEsewaException() {
            assertThatThrownBy(() -> esewaClient.verifyCallback(null))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("eSewa callback data cannot be null or blank");
        }

        @Test
        @DisplayName("throws EsewaException when encodedData is not valid Base64")
        void verifyCallback_invalidBase64_throwsEsewaException() {
            assertThatThrownBy(() -> esewaClient.verifyCallback("NOT_VALID_BASE64!!"))
                    .isInstanceOf(EsewaException.class);
        }

        @Test
        @DisplayName("success: valid Base64 callback with correct signature")
        void verifyCallback_validSignedCallback_returnsVerifiedResult()
                throws Exception {

            // Build a real signed callback JSON — same as eSewa would send
            String transactionCode = "000ABCD";
            String status          = "COMPLETE";
            String totalAmount     = "100.00";
            String productCode     = SANDBOX_PRODUCT_CODE;
            String signedFieldNames =
                    "transaction_code,status,total_amount,"
                            + "transaction_uuid,product_code,signed_field_names";

            // Compute a real signature for the callback
            String message = "transaction_code=" + transactionCode
                    + ",status=" + status
                    + ",total_amount=" + totalAmount
                    + ",transaction_uuid=" + TEST_UUID
                    + ",product_code=" + productCode
                    + ",signed_field_names=" + signedFieldNames;

            String realSignature = computeHmacSha256(message, SANDBOX_SECRET_KEY);

            // Build the JSON eSewa would send as callback data
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
                    """.formatted(
                    transactionCode, status, totalAmount, TEST_UUID,
                    productCode, signedFieldNames, realSignature);

            // Encode as Base64 — exactly how eSewa sends it
            String encodedData = Base64.getEncoder()
                    .encodeToString(callbackJson.getBytes(StandardCharsets.UTF_8));

            // Queue status API response (called inside verifyCallback)
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                                "product_code": "EPAYTEST",
                                "transaction_uuid": "%s",
                                "total_amount": 100.0,
                                "status": "COMPLETE",
                                "ref_id": "0001TS9"
                            }
                            """.formatted(TEST_UUID)));

            // Act
            EsewaClient.EsewaVerificationResult result =
                    esewaClient.verifyCallback(encodedData);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.verified()).isTrue();
            assertThat(result.isPaymentSuccessful()).isTrue();
            assertThat(result.callbackData().transactionUuid()).isEqualTo(TEST_UUID);
            assertThat(result.callbackData().status()).isEqualTo("COMPLETE");
            assertThat(result.statusResponse().refId()).isEqualTo("0001TS9");
        }

        @Test
        @DisplayName("throws EsewaException when signature is tampered")
        void verifyCallback_tamperedSignature_throwsEsewaException() throws Exception {
            // Build callback JSON with a WRONG/tampered signature
            String callbackJson = """
                    {
                        "transaction_code": "000ABCD",
                        "status": "COMPLETE",
                        "total_amount": "100.00",
                        "transaction_uuid": "%s",
                        "product_code": "%s",
                        "signed_field_names": "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names",
                        "signature": "TAMPERED_SIGNATURE_THAT_IS_WRONG=="
                    }
                    """.formatted(TEST_UUID, SANDBOX_PRODUCT_CODE);

            String encodedData = Base64.getEncoder()
                    .encodeToString(callbackJson.getBytes(StandardCharsets.UTF_8));

            // Act + Assert
            assertThatThrownBy(() -> esewaClient.verifyCallback(encodedData))
                    .isInstanceOf(EsewaException.class)
                    .hasMessageContaining("signature verification FAILED");
        }
    }

    // ── generateTransactionUuid() ─────────────────────────────────────────────

    @Test
    @DisplayName("generateTransactionUuid: returns non-blank unique string")
    void generateTransactionUuid_returnsUniqueNonBlankString() {
        String uuid1 = EsewaClient.generateTransactionUuid();
        String uuid2 = EsewaClient.generateTransactionUuid();

        assertThat(uuid1).isNotBlank();
        assertThat(uuid2).isNotBlank();
        assertThat(uuid1).isNotEqualTo(uuid2); // Must be unique
    }

    @Test
    @DisplayName("isSandbox() returns true when sandbox=true")
    void isSandbox_returnsTrueWhenSandboxEnabled() {
        assertThat(esewaClient.isSandbox()).isTrue();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private NepalPayProperties.EsewaProperties buildEsewaProperties() {
        return new NepalPayProperties.EsewaProperties(
                SANDBOX_SECRET_KEY,
                SANDBOX_PRODUCT_CODE,
                SUCCESS_URL,
                FAILURE_URL,
                true,
                10
        );
    }

    /**
     * Manually compute HMAC-SHA256 for test assertions.
     * Used to verify EsewaClient generates the correct signature.
     */
    private String computeHmacSha256(String message, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }
}