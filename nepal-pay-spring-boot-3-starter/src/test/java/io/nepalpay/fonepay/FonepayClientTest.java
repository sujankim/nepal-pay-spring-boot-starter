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
 * Unit tests for FonepayClient.
 *
 * <p>NOTE: FonepayClient does NOT use RestClient or make HTTP calls.
 * Fonepay uses a URL redirect model — all logic is local HMAC signing.
 * Therefore NO MockWebServer is needed for these tests.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>buildRedirectParams() — HMAC-SHA512 signature correctness</li>
 *   <li>buildRedirectParams() — redirect URL construction</li>
 *   <li>buildRedirectParams() — request validation (PRN, amount, remarks)</li>
 *   <li>buildRedirectParams() — config validation (missing keys)</li>
 *   <li>verifyCallback()      — valid HMAC-SHA512 signature passes</li>
 *   <li>verifyCallback()      — tampered signature throws FonepayException</li>
 *   <li>verifyCallback()      — successful payment status</li>
 *   <li>verifyCallback()      — failed payment status</li>
 *   <li>isSandbox(), gatewayUrl() utility methods</li>
 *   <li>FonepayPaymentRequest builder</li>
 * </ul>
 */
@DisplayName("FonepayClient")
class FonepayClientTest {

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private static final String MERCHANT_CODE = "TEST_MERCHANT";
    private static final String SECRET_KEY    = "test_secret_key_for_fonepay_12345";
    private static final String RETURN_URL    = "http://localhost:8080/fonepay/callback";
    private static final String MOCK_GATEWAY  = "https://test-gateway.fonepay.com/api/merchantRequest";
    private static final String PRN           = "FP-ORD-001";

    private FonepayClient fonepayClient;
    private NepalPayProperties.FonepayProperties props;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        props = buildFonepayProperties();

        // Use test constructor with custom gateway URL
        fonepayClient = new FonepayClient(props, MOCK_GATEWAY);
    }

    // ── buildRedirectParams() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("buildRedirectParams()")
    class BuildRedirectParams {

        @Test
        @DisplayName("success: all redirect params are populated correctly")
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
                            .prn(PRN)
                            .amount(100.0)
                            .remarks1("Test Payment")
                            .build()
            );

            String url = params.redirectUrl();
            assertThat(url).isNotBlank();
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
                            .prn(PRN)
                            .amount(100.0)
                            .remarks1("Payment")
                            .build()
            );

            assertThat(params.redirectUrl()).startsWith(MOCK_GATEWAY);
        }

        @Test
        @DisplayName("HMAC-SHA512 signature is correct — verified against known input")
        void buildRedirectParams_hmacSignature_isCorrect() throws Exception {
            double amount = 100.0;
            String remarks1 = "Pro Plan";
            String remarks2 = "NepalPay";

            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN)
                            .amount(amount)
                            .remarks1(remarks1)
                            .remarks2(remarks2)
                            .build()
            );

            // Manually compute expected HMAC-SHA512
            // Message order: PID,MD,PRN,AMT,CRN,DT,R1,R2,RU
            String date   = LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));
            String amtStr = "100"; // 100.0 → "100" (no trailing zeros for whole numbers)
            String message = MERCHANT_CODE + ",P," + PRN + ","
                    + amtStr + ",NPR," + date + ","
                    + remarks1 + "," + remarks2 + "," + RETURN_URL;

            String expectedDv = computeHmacSha512Hex(message, SECRET_KEY);

            assertThat(params.dv()).isEqualTo(expectedDv);
        }

        @Test
        @DisplayName("amount formatting: whole numbers have no decimal point")
        void buildRedirectParams_amount_wholeNumberFormatting() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN)
                            .amount(100.0)
                            .remarks1("Payment")
                            .build()
            );

            assertThat(params.amt()).isEqualTo("100");
        }

        @Test
        @DisplayName("amount formatting: decimal amounts preserved")
        void buildRedirectParams_amount_decimalFormatting() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN)
                            .amount(150.5)
                            .remarks1("Payment")
                            .build()
            );

            assertThat(params.amt()).isEqualTo("150.5");
        }

        @Test
        @DisplayName("remarks2 defaults to empty string when null")
        void buildRedirectParams_remarks2Null_defaultsToEmpty() {
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(PRN)
                            .amount(100.0)
                            .remarks1("Payment")
                            // no remarks2 set — defaults to ""
                            .build()
            );

            assertThat(params.r2()).isEmpty();
        }

        // ── Validation ────────────────────────────────────────────────────────

        @Test
        @DisplayName("throws FonepayException when PRN is null")
        void buildRedirectParams_nullPrn_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(null)
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN (Product Reference Number) is required");
        }

        @Test
        @DisplayName("throws FonepayException when PRN is blank")
        void buildRedirectParams_blankPrn_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn("  ")
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN (Product Reference Number) is required");
        }

        @Test
        @DisplayName("throws FonepayException when PRN is too short (less than 3 chars)")
        void buildRedirectParams_prnTooShort_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn("AB")         // 2 chars — minimum is 3
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN must be between 3 and 25 characters");
        }

        @Test
        @DisplayName("throws FonepayException when PRN is too long (over 25 chars)")
        void buildRedirectParams_prnTooLong_throwsFonepayException() {
            String longPrn = "A".repeat(26);  // 26 chars — maximum is 25
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(longPrn)
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("PRN must be between 3 and 25 characters");
        }

        @Test
        @DisplayName("PRN at exactly 25 chars is accepted")
        void buildRedirectParams_prnExactly25Chars_isAccepted() {
            String prn25 = "A".repeat(25);

            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(prn25)
                            .amount(100.0)
                            .remarks1("Payment")
                            .build()
            );

            assertThat(params.prn()).isEqualTo(prn25);
        }

        @Test
        @DisplayName("throws FonepayException when amount is zero")
        void buildRedirectParams_zeroAmount_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(0.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws FonepayException when amount is negative")
        void buildRedirectParams_negativeAmount_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(-100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Amount must be greater than 0");
        }

        @Test
        @DisplayName("throws FonepayException when remarks1 is blank")
        void buildRedirectParams_blankRemarks1_throwsFonepayException() {
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(100.0)
                                    .remarks1("  ")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("remarks1 is required and cannot be blank");
        }

        @Test
        @DisplayName("throws FonepayException when remarks1 exceeds 160 characters")
        void buildRedirectParams_remarks1TooLong_throwsFonepayException() {
            String longRemarks = "A".repeat(161);
            assertThatThrownBy(() ->
                    fonepayClient.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(100.0)
                                    .remarks1(longRemarks)
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("remarks1 must not exceed 160 characters");
        }

        // ── Config validation ─────────────────────────────────────────────────

        @Test
        @DisplayName("throws FonepayException when merchant code is blank")
        void buildRedirectParams_blankMerchantCode_throwsFonepayException() {
            NepalPayProperties.FonepayProperties emptyProps =
                    new NepalPayProperties.FonepayProperties(
                            "",           // ← blank merchant code
                            SECRET_KEY,
                            RETURN_URL,
                            true
                    );
            FonepayClient clientNoMerchant = new FonepayClient(emptyProps, MOCK_GATEWAY);

            assertThatThrownBy(() ->
                    clientNoMerchant.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("merchant code not configured");
        }

        @Test
        @DisplayName("throws FonepayException when secret key is blank")
        void buildRedirectParams_blankSecretKey_throwsFonepayException() {
            NepalPayProperties.FonepayProperties emptyProps =
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE,
                            "",           // ← blank secret key
                            RETURN_URL,
                            true
                    );
            FonepayClient clientNoKey = new FonepayClient(emptyProps, MOCK_GATEWAY);

            assertThatThrownBy(() ->
                    clientNoKey.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("secret key not configured");
        }

        @Test
        @DisplayName("throws FonepayException when return URL is blank")
        void buildRedirectParams_blankReturnUrl_throwsFonepayException() {
            NepalPayProperties.FonepayProperties emptyProps =
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE,
                            SECRET_KEY,
                            "",           // ← blank return URL
                            true
                    );
            FonepayClient clientNoUrl = new FonepayClient(emptyProps, MOCK_GATEWAY);

            assertThatThrownBy(() ->
                    clientNoUrl.buildRedirectParams(
                            FonepayPaymentRequest.builder()
                                    .prn(PRN)
                                    .amount(100.0)
                                    .remarks1("Payment")
                                    .build()
                    ))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("return URL not configured");
        }
    }

    // ── verifyCallback() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyCallback()")
    class VerifyCallback {

        @Test
        @DisplayName("success: valid HMAC signature + PS=success → verified=true")
        void verifyCallback_validSignature_successStatus_returnsVerified()
                throws Exception {

            // Build the exact message Fonepay sends in callback
            // Order: PRN,PID,PS,RC,UID,BC,INI,P_AMT,R_AMT
            String ps   = "success";
            String rc   = "200";
            String uid  = "fonepay-uid-001";
            String bc   = "GBIME";
            String ini  = "9800000001";
            String pAmt = "100";
            String rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + ","
                    + pAmt + "," + rAmt;

            // Compute real HMAC-SHA512 → uppercase hex (as Fonepay sends it)
            String dv = computeHmacSha512Hex(message, SECRET_KEY).toUpperCase();

            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv);

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(callback);

            assertThat(result).isNotNull();
            assertThat(result.verified()).isTrue();
            assertThat(result.isPaymentSuccessful()).isTrue();
            assertThat(result.callbackResponse().prn()).isEqualTo(PRN);
            assertThat(result.callbackResponse().ps()).isEqualTo("success");
        }

        @Test
        @DisplayName("valid signature + PS=failed → verified=false (signature OK but payment failed)")
        void verifyCallback_validSignature_failedStatus_returnsFalse() throws Exception {
            String ps   = "failed";
            String rc   = "400";
            String uid  = "fonepay-uid-002";
            String bc   = "GBIME";
            String ini  = "9800000001";
            String pAmt = "0";
            String rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + ","
                    + pAmt + "," + rAmt;

            String dv = computeHmacSha512Hex(message, SECRET_KEY).toUpperCase();

            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv);

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(callback);

            // Signature is valid — but payment status is failed
            assertThat(result.verified()).isFalse();
            assertThat(result.isPaymentSuccessful()).isFalse();
        }

        @Test
        @DisplayName("throws FonepayException when DV signature is tampered")
        void verifyCallback_tamperedDv_throwsFonepayException() {
            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN,
                            MERCHANT_CODE,
                            "success",
                            "200",
                            "uid-001",
                            "GBIME",
                            "9800000001",
                            "100",
                            "0",
                            "TAMPERED_HMAC_SIGNATURE_THAT_IS_WRONG"  // ← tampered!
                    );

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("signature verification FAILED");
        }

        @Test
        @DisplayName("throws FonepayException when callback is null")
        void verifyCallback_nullCallback_throwsFonepayException() {
            assertThatThrownBy(() -> fonepayClient.verifyCallback(null))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("throws FonepayException when PRN is missing in callback")
        void verifyCallback_missingPrn_throwsFonepayException() {
            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            "",   // ← blank PRN
                            MERCHANT_CODE, "success", "200",
                            "uid", "BC", "ini", "100", "0", "DV_VALUE"
                    );

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Callback PRN is missing");
        }

        @Test
        @DisplayName("throws FonepayException when DV is missing in callback")
        void verifyCallback_missingDv_throwsFonepayException() {
            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN, MERCHANT_CODE, "success", "200",
                            "uid", "BC", "ini", "100", "0",
                            ""    // ← blank DV
                    );

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Callback DV (signature) is missing");
        }

        @Test
        @DisplayName("throws FonepayException when PS is missing in callback")
        void verifyCallback_missingPs_throwsFonepayException() {
            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN, MERCHANT_CODE,
                            "",   // ← blank PS
                            "200", "uid", "BC", "ini", "100", "0", "DV_VALUE"
                    );

            assertThatThrownBy(() -> fonepayClient.verifyCallback(callback))
                    .isInstanceOf(FonepayException.class)
                    .hasMessageContaining("Callback PS (payment status) is missing");
        }

        @Test
        @DisplayName("DV comparison is case-insensitive — lowercase DV also matches")
        void verifyCallback_lowercaseDv_alsoMatches() throws Exception {
            String ps   = "success";
            String rc   = "200";
            String uid  = "fonepay-uid-001";
            String bc   = "GBIME";
            String ini  = "9800000001";
            String pAmt = "100";
            String rAmt = "0";

            String message = PRN + "," + MERCHANT_CODE + "," + ps + ","
                    + rc + "," + uid + "," + bc + "," + ini + ","
                    + pAmt + "," + rAmt;

            // Lowercase DV — FonepayClient does .toUpperCase() before comparing
            String dv = computeHmacSha512Hex(message, SECRET_KEY).toLowerCase();

            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(
                            PRN, MERCHANT_CODE, ps, rc, uid, bc, ini, pAmt, rAmt, dv);

            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(callback);

            assertThat(result.isPaymentSuccessful()).isTrue();
        }
    }

    // ── FonepayPaymentRequest builder ─────────────────────────────────────────

    @Nested
    @DisplayName("FonepayPaymentRequest builder")
    class PaymentRequestBuilder {

        @Test
        @DisplayName("builder: sets all fields correctly")
        void builder_setsAllFields() {
            FonepayPaymentRequest request = FonepayPaymentRequest.builder()
                    .prn("FP-ORD-001")
                    .amount(100.0)
                    .remarks1("Pro Plan")
                    .remarks2("NepalPay Demo")
                    .build();

            assertThat(request.prn()).isEqualTo("FP-ORD-001");
            assertThat(request.amount()).isEqualTo(100.0);
            assertThat(request.remarks1()).isEqualTo("Pro Plan");
            assertThat(request.remarks2()).isEqualTo("NepalPay Demo");
        }

        @Test
        @DisplayName("remarks1 defaults to 'Payment'")
        void builder_remarks1DefaultsToPayment() {
            FonepayPaymentRequest request = FonepayPaymentRequest.builder()
                    .prn("FP-001")
                    .amount(100.0)
                    .build();

            assertThat(request.remarks1()).isEqualTo("Payment");
        }

        @Test
        @DisplayName("remarks2 defaults to empty string")
        void builder_remarks2DefaultsToEmpty() {
            FonepayPaymentRequest request = FonepayPaymentRequest.builder()
                    .prn("FP-001")
                    .amount(100.0)
                    .build();

            assertThat(request.remarks2()).isEmpty();
        }
    }

    // ── Utility methods ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("isSandbox() returns true when sandbox=true")
        void isSandbox_returnsTrueWhenSandboxEnabled() {
            assertThat(fonepayClient.isSandbox()).isTrue();
        }

        @Test
        @DisplayName("isSandbox() returns false when sandbox=false")
        void isSandbox_returnsFalseWhenProductionMode() {
            NepalPayProperties.FonepayProperties prodProps =
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE, SECRET_KEY, RETURN_URL, false
                    );
            FonepayClient prodClient = new FonepayClient(prodProps);
            assertThat(prodClient.isSandbox()).isFalse();
        }

        @Test
        @DisplayName("gatewayUrl() returns custom URL when set via test constructor")
        void gatewayUrl_returnsCustomUrl_whenTestConstructorUsed() {
            assertThat(fonepayClient.gatewayUrl()).isEqualTo(MOCK_GATEWAY);
        }

        @Test
        @DisplayName("sandbox=true gives dev.fonepay.com gateway URL")
        void sandboxTrue_givesSandboxGatewayUrl() {
            FonepayClient sandboxClient = new FonepayClient(props);
            assertThat(sandboxClient.gatewayUrl())
                    .isEqualTo("https://dev.fonepay.com/api/merchantRequest");
        }

        @Test
        @DisplayName("sandbox=false gives production fonepay.com gateway URL")
        void sandboxFalse_givesProductionGatewayUrl() {
            NepalPayProperties.FonepayProperties prodProps =
                    new NepalPayProperties.FonepayProperties(
                            MERCHANT_CODE, SECRET_KEY, RETURN_URL, false
                    );
            FonepayClient prodClient = new FonepayClient(prodProps);
            assertThat(prodClient.gatewayUrl())
                    .isEqualTo("https://fonepay.com/api/merchantRequest");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private NepalPayProperties.FonepayProperties buildFonepayProperties() {
        return new NepalPayProperties.FonepayProperties(
                MERCHANT_CODE,
                SECRET_KEY,
                RETURN_URL,
                true
        );
    }

    /**
     * Manually compute HMAC-SHA512 as lowercase hexadecimal.
     * Used to compute expected DV values for test assertions.
     */
    private String computeHmacSha512Hex(String message, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(rawHmac);
    }
}