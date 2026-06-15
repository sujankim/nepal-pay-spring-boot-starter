package io.nepalpay.fonepay;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.FonepayException;
import io.nepalpay.core.fonepay.model.FonepayCallbackResponse;
import io.nepalpay.core.fonepay.model.FonepayPaymentRequest;
import io.nepalpay.core.fonepay.model.FonepayRedirectParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for FonepayClient — Spring Boot 4 variant.
 *
 * <p>FonepayClient is IDENTICAL in Boot 3 and Boot 4
 * (no Jackson dependency — redirect only).
 * This test file mirrors Boot 3's FonepayClientTest exactly.
 *
 * <p>No MockWebServer needed — Fonepay makes no server-to-server HTTP calls.
 */
@DisplayName("FonepayClient")
class FonepayClientTest {

    private static final String MERCHANT_CODE = "TEST_MERCHANT";
    private static final String SECRET_KEY    = "test_secret_key_for_fonepay_12345";
    private static final String RETURN_URL    = "http://localhost:8080/fonepay/callback";
    private static final String MOCK_GATEWAY  = "https://test-gateway.fonepay.com/api/merchantRequest";
    private static final String PRN           = "FP-ORD-001";

    private FonepayClient fonepayClient;
    private NepalPayProperties.FonepayProperties props;

    @BeforeEach
    void setUp() {
        props = buildFonepayProperties();
        fonepayClient = new FonepayClient(props, MOCK_GATEWAY);
    }

    @Nested
    @DisplayName("buildRedirectParams()")
    class BuildRedirectParams {

        @Test
        @DisplayName("success: all redirect params populated correctly")
        void buildRedirectParams_success_allFieldsPopulated() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN)
                            .amount(100.0)
                            .remarks1("Pro Plan")
                            .remarks2("NepalPay Demo")
                            .build()
            );

            assertThat(params).isNotNull();
            assertThat(params.pid()).isEqualTo(MERCHANT_CODE);
            assertThat(params.md()).isEqualTo("P");
            assertThat(params.prn()).isEqualTo(PRN);
            assertThat(params.amt()).isEqualTo("100");
            assertThat(params.crn()).isEqualTo("NPR");
            assertThat(params.r1()).isEqualTo("Pro Plan");
            assertThat(params.r2()).isEqualTo("NepalPay Demo");
            assertThat(params.ru()).isEqualTo(RETURN_URL);
            assertThat(params.dv()).isNotBlank();
        }

        @Test
        @DisplayName("redirectUrl contains all required query parameters")
        void buildRedirectParams_redirectUrl_containsAllParams() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("Test Payment").build()
            );

            String url = params.redirectUrl();
            assertThat(url).contains("PID=" + MERCHANT_CODE);
            assertThat(url).contains("MD=P");
            assertThat(url).contains("PRN=" + PRN);
            assertThat(url).contains("AMT=100");
            assertThat(url).contains("CRN=NPR");
            assertThat(url).contains("DV=");
        }

        @Test
        @DisplayName("redirectUrl starts with custom gateway URL")
        void buildRedirectParams_redirectUrl_startsWithGatewayUrl() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("Payment").build()
            );
            assertThat(params.redirectUrl()).startsWith(MOCK_GATEWAY);
        }

        @Test
        @DisplayName("HMAC-SHA512 signature is correct")
        void buildRedirectParams_hmacSignature_isCorrect() throws Exception {
            double amount   = 100.0;
            String remarks1 = "Pro Plan";
            String remarks2 = "NepalPay";

            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(amount)
                            .remarks1(remarks1).remarks2(remarks2).build()
            );

            String date    = LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));
            String amtStr  = "100";
            String message = MERCHANT_CODE + ",P," + PRN + ","
                    + amtStr + ",NPR," + date + ","
                    + remarks1 + "," + remarks2 + "," + RETURN_URL;

            String expectedDv = computeHmacSha512Hex(message, SECRET_KEY);
            assertThat(params.dv()).isEqualTo(expectedDv);
        }

        @Test
        @DisplayName("amount formatting: whole numbers have no decimal point")
        void buildRedirectParams_wholeAmount_noDecimal() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("Payment").build()
            );
            assertThat(params.amt()).isEqualTo("100");
        }

        @Test
        @DisplayName("amount formatting: decimal amounts preserved")
        void buildRedirectParams_decimalAmount_preserved() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(150.5).remarks1("Payment").build()
            );
            assertThat(params.amt()).isEqualTo("150.5");
        }

        @Test
        @DisplayName("throws when PRN is null")
        void buildRedirectParams_nullPrn_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(null).amount(100.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN (Product Reference Number) is required");
        }

        @Test
        @DisplayName("throws when PRN is too short")
        void buildRedirectParams_prnTooShort_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn("AB").amount(100.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN must be between 3 and 25 characters");
        }

        @Test
        @DisplayName("throws when PRN is too long")
        void buildRedirectParams_prnTooLong_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn("A".repeat(26)).amount(100.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN must be between 3 and 25 characters");
        }

        @Test
        @DisplayName("throws when amount is zero")
        void buildRedirectParams_zeroAmount_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(0.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws when remarks1 is blank")
        void buildRedirectParams_blankRemarks1_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("  ").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("remarks1 is required");
        }

        @Test
        @DisplayName("throws when remarks1 exceeds 160 chars")
        void buildRedirectParams_remarks1TooLong_throws() {
            assertThatThrownBy(() -> fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("A".repeat(161)).build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("remarks1 must not exceed 160 characters");
        }

        @Test
        @DisplayName("throws when merchant code is blank")
        void buildRedirectParams_blankMerchantCode_throws() {
            FonepayClient client = new FonepayClient(
                    new NepalPayProperties.FonepayProperties(
                            "", SECRET_KEY, RETURN_URL, true), MOCK_GATEWAY);

            assertThatThrownBy(() -> client.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("merchant code not configured");
        }

        @Test
        @DisplayName("throws when secret key is blank")
        void buildRedirectParams_blankSecretKey_throws() {
            FonepayClient client = new FonepayClient(
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE, "", RETURN_URL, true), MOCK_GATEWAY);

            assertThatThrownBy(() -> client.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN).amount(100.0).remarks1("Payment").build()
            )).isInstanceOf(FonepayException.class)
                    .hasMessageContaining("secret key not configured");
        }
    }

    @Nested
    @DisplayName("verifyCallback()")
    class VerifyCallback {

        @Test
        @DisplayName("valid HMAC + PS=success → verified=true")
        void verifyCallback_validSignatureSuccessStatus_verified() throws Exception {
            String ps = "success", rc = "200", uid = "uid-001",
                    bc = "GBIME", ini = "9800000001", pAmt = "100", rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + "," + pAmt + "," + rAmt;
            String dv = computeHmacSha512Hex(message, SECRET_KEY).toUpperCase();

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(
                            FonepayCallbackResponse.of(
                                    PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv));

            assertThat(result.isPaymentSuccessful()).isTrue();
            assertThat(result.verified()).isTrue();
        }

        @Test
        @DisplayName("valid HMAC + PS=failed → verified=false")
        void verifyCallback_validSignatureFailedStatus_notVerified() throws Exception {
            String ps = "failed", rc = "400", uid = "uid-002",
                    bc = "GBIME", ini = "9800000001", pAmt = "0", rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + "," + pAmt + "," + rAmt;
            String dv = computeHmacSha512Hex(message, SECRET_KEY).toUpperCase();

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(
                            FonepayCallbackResponse.of(
                                    PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv));

            assertThat(result.isPaymentSuccessful()).isFalse();
        }

        @Test
        @DisplayName("tampered DV throws FonepayException")
        void verifyCallback_tamperedDv_throws() {
            FonepayCallbackResponse callback = FonepayCallbackResponse.of(
                    PRN, MERCHANT_CODE, "success", "200",
                    "uid", "BC", "ini", "100", "0",
                    "TOTALLY_WRONG_SIGNATURE");

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("signature verification FAILED");
        }

        @Test
        @DisplayName("null callback throws FonepayException")
        void verifyCallback_null_throws() {
            assertThatThrownBy(() -> fonepayClient.verifyCallback(null))
                    .isInstanceOf(FonepayException.class);
        }

        @Test
        @DisplayName("missing PRN throws FonepayException")
        void verifyCallback_missingPrn_throws() {
            FonepayCallbackResponse callback = FonepayCallbackResponse.of(
                    "", MERCHANT_CODE, "success", "200",
                    "uid", "BC", "ini", "100", "0", "DV");

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Callback PRN is missing");
        }

        @Test
        @DisplayName("missing DV throws FonepayException")
        void verifyCallback_missingDv_throws() {
            FonepayCallbackResponse callback = FonepayCallbackResponse.of(
                    PRN, MERCHANT_CODE, "success", "200",
                    "uid", "BC", "ini", "100", "0", "");

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Callback DV (signature) is missing");
        }

        @Test
        @DisplayName("lowercase DV is accepted — comparison is case-insensitive")
        void verifyCallback_lowercaseDv_accepted() throws Exception {
            String ps = "success", rc = "200", uid = "uid-001",
                    bc = "GBIME", ini = "9800000001", pAmt = "100", rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + "," + pAmt + "," + rAmt;

            // lowercase DV — client converts both to uppercase before comparing
            String dv = computeHmacSha512Hex(message, SECRET_KEY).toLowerCase();

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(
                            FonepayCallbackResponse.of(
                                    PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv));

            assertThat(result.isPaymentSuccessful()).isTrue();
        }
    }

    @Nested
    @DisplayName("FonepayPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("builder sets all fields")
        void builder_setsAllFields() {
            FonepayPaymentRequest req = FonepayPaymentRequest.builder()
                    .prn("FP-001").amount(100.0)
                    .remarks1("Pro Plan").remarks2("Demo").build();

            assertThat(req.prn()).isEqualTo("FP-001");
            assertThat(req.amount()).isEqualTo(100.0);
            assertThat(req.remarks1()).isEqualTo("Pro Plan");
            assertThat(req.remarks2()).isEqualTo("Demo");
        }

        @Test
        @DisplayName("remarks1 defaults to 'Payment'")
        void builder_remarks1DefaultsToPayment() {
            FonepayPaymentRequest req = FonepayPaymentRequest.builder()
                    .prn("FP-001").amount(100.0).build();
            assertThat(req.remarks1()).isEqualTo("Payment");
        }

        @Test
        @DisplayName("remarks2 defaults to empty string")
        void builder_remarks2DefaultsToEmpty() {
            FonepayPaymentRequest req = FonepayPaymentRequest.builder()
                    .prn("FP-001").amount(100.0).build();
            assertThat(req.remarks2()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("isSandbox() true when sandbox=true")
        void isSandbox_trueSandbox() {
            assertThat(fonepayClient.isSandbox()).isTrue();
        }

        @Test
        @DisplayName("isSandbox() false when sandbox=false")
        void isSandbox_falseProduction() {
            FonepayClient prod = new FonepayClient(
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE, SECRET_KEY, RETURN_URL, false));
            assertThat(prod.isSandbox()).isFalse();
        }

        @Test
        @DisplayName("sandbox=true gives dev.fonepay.com URL")
        void sandboxTrue_sandboxGatewayUrl() {
            FonepayClient sandbox = new FonepayClient(props);
            assertThat(sandbox.gatewayUrl())
                    .isEqualTo("https://dev.fonepay.com/api/merchantRequest");
        }

        @Test
        @DisplayName("sandbox=false gives fonepay.com URL")
        void sandboxFalse_productionGatewayUrl() {
            FonepayClient prod = new FonepayClient(
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE, SECRET_KEY, RETURN_URL, false));
            assertThat(prod.gatewayUrl())
                    .isEqualTo("https://fonepay.com/api/merchantRequest");
        }

        @Test
        @DisplayName("gatewayUrl() returns custom URL from test constructor")
        void gatewayUrl_customUrl() {
            assertThat(fonepayClient.gatewayUrl()).isEqualTo(MOCK_GATEWAY);
        }
    }

    private NepalPayProperties.FonepayProperties buildFonepayProperties() {
        return new NepalPayProperties.FonepayProperties(
                MERCHANT_CODE, SECRET_KEY, RETURN_URL, true);
    }

    private String computeHmacSha512Hex(String message, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}