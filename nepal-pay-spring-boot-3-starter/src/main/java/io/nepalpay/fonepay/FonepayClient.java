package io.nepalpay.fonepay;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.FonepayException;
import io.nepalpay.core.fonepay.model.FonepayCallbackResponse;
import io.nepalpay.core.fonepay.model.FonepayPaymentRequest;
import io.nepalpay.core.fonepay.model.FonepayRedirectParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Fonepay Payment Gateway Client for Spring Boot.
 *
 * <p>Fonepay uses a <strong>URL redirect model</strong>:
 * Your backend builds signed redirect parameters, constructs the redirect URL,
 * and the frontend redirects the user directly to Fonepay.
 *
 * <p>Signature algorithm: HMAC-SHA512, hex output (not Base64 like eSewa).
 *
 * <p>This client provides:
 * <ul>
 *   <li>{@link #buildRedirectParams} — Build signed redirect URL params</li>
 *   <li>{@link #verifyCallback}      — Verify HMAC signature + check status</li>
 * </ul>
 *
 * <p>Official Fonepay docs: Fonepay Web Integration Technical Specification
 *
 * <p>Complete integration flow:
 * <pre>{@code
 * // Step 1: Build redirect params
 * FonepayRedirectParams params = fonepayClient.buildRedirectParams(
 *     FonepayPaymentRequest.builder()
 *         .prn("ORD-001-" + System.currentTimeMillis())
 *         .amount(100.0)
 *         .remarks1("Pro Plan")
 *         .build()
 * );
 * // Return params.redirectUrl() to frontend
 * // Frontend: window.location.href = redirectUrl
 *
 * // Step 2: Verify callback
 * FonepayCallbackResponse callback = FonepayCallbackResponse.of(
 *     prn, pid, ps, rc, uid, bc, ini, pAmt, rAmt, dv);
 * FonepayClient.FonepayVerificationResult result =
 *     fonepayClient.verifyCallback(callback);
 * if (result.isPaymentSuccessful()) {
 *     // safe to mark as paid
 * }
 * }</pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class FonepayClient {

    // Official Fonepay gateway URLs
    private static final String SANDBOX_URL    = "https://dev.fonepay.com/api/merchantRequest";
    private static final String PRODUCTION_URL = "https://fonepay.com/api/merchantRequest";

    // Fonepay constants
    private static final String PAYMENT_MODE = "P";   // always "P" for payment
    private static final String CURRENCY     = "NPR"; // always NPR

    // Date format required by Fonepay: MM/DD/YYYY
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    // HMAC-SHA512 algorithm
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private final NepalPayProperties.FonepayProperties props;
    private final String gatewayUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     *
     * <p>Gateway URL is determined from {@code nepalpay.fonepay.sandbox}:
     * <ul>
     *   <li>sandbox=true  → https://dev.fonepay.com/api/merchantRequest</li>
     *   <li>sandbox=false → https://fonepay.com/api/merchantRequest</li>
     * </ul>
     *
     * @param props Fonepay properties from application.yml
     */
    public FonepayClient(NepalPayProperties.FonepayProperties props) {
        this.props      = props;
        this.gatewayUrl = props.sandbox() ? SANDBOX_URL : PRODUCTION_URL;

        log.info("[NepalPay] FonepayClient initialized | mode={} | merchantCode={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                props.merchantCode());
    }

    /**
     * Test constructor — allows injecting a custom gateway URL.
     *
     * <p>Used in tests to override the Fonepay gateway URL.
     *
     * @param props             Fonepay properties
     * @param gatewayUrlOverride custom gateway URL for testing
     */
    public FonepayClient(
            NepalPayProperties.FonepayProperties props,
            String gatewayUrlOverride) {

        this.props      = props;
        this.gatewayUrl = gatewayUrlOverride;

        log.info("[NepalPay] FonepayClient initialized (test mode) | gatewayUrl={}",
                gatewayUrlOverride);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build signed Fonepay redirect parameters from a request object.
     *
     * <p>Convenience overload that accepts a {@link FonepayPaymentRequest}.
     *
     * @param request payment request built via {@link FonepayPaymentRequest#builder()}
     * @return signed redirect params containing the full redirect URL
     * @throws FonepayException if validation fails or signature generation fails
     */
    public FonepayRedirectParams buildRedirectParams(FonepayPaymentRequest request) {
        return buildRedirectParams(
                request.prn(),
                request.amount(),
                request.remarks1(),
                request.remarks2() != null ? request.remarks2() : ""
        );
    }

    /**
     * Build signed Fonepay redirect parameters.
     *
     * <p>Signature algorithm (field order is MANDATORY per Fonepay docs):
     * <pre>
     * Concatenate: PID,MD,PRN,AMT,CRN,DT,R1,R2,RU
     * Hash with:   HMAC-SHA512(secretKey, message, UTF-8)
     * Output:      lowercase hexadecimal
     * </pre>
     *
     * <p>Amount note: Fonepay uses NPR as a double. NPR 100 → send {@code 100.0}.
     * This is different from Khalti (paisa) and eSewa (BigDecimal NPR).
     *
     * @param prn      Unique Product Reference Number (3-25 chars, store in DB!)
     * @param amount   Amount in NPR as a double
     * @param remarks1 Payment description (max 160 chars)
     * @param remarks2 Additional remarks (max 50 chars, empty string if none)
     * @return signed redirect params with full redirect URL
     * @throws FonepayException if config is invalid or signature generation fails
     */
    public FonepayRedirectParams buildRedirectParams(
            String prn,
            double amount,
            String remarks1,
            String remarks2) {

        validateConfig();
        validateRequest(prn, amount, remarks1);

        String merchantCode = props.merchantCode();
        String returnUrl    = props.returnUrl();
        String date         = LocalDate.now().format(DATE_FORMAT);
        String amtStr       = formatAmount(amount);
        String r1           = remarks1 != null ? remarks1 : "Payment";
        String r2           = remarks2 != null ? remarks2 : "";

        // Build signature message — field order is MANDATORY per Fonepay spec:
        // PID,MD,PRN,AMT,CRN,DT,R1,R2,RU
        String message = merchantCode + "," + PAYMENT_MODE + "," + prn + ","
                + amtStr + "," + CURRENCY + "," + date + ","
                + r1 + "," + r2 + "," + returnUrl;

        String dv = generateHmacSha512Hex(message, props.secretKey());

        // Build full redirect URL with all params as query string
        String redirectUrl = UriComponentsBuilder.fromUriString(gatewayUrl)
                .queryParam("PID", merchantCode)
                .queryParam("MD",  PAYMENT_MODE)
                .queryParam("PRN", prn)
                .queryParam("AMT", amtStr)
                .queryParam("CRN", CURRENCY)
                .queryParam("DT",  date)
                .queryParam("R1",  r1)
                .queryParam("R2",  r2)
                .queryParam("DV",  dv)
                .queryParam("RU",  returnUrl)
                .build()
                .toUriString();

        log.debug("[NepalPay] Fonepay redirect built | prn={} | amt={} | date={}",
                prn, amtStr, date);

        return new FonepayRedirectParams(
                merchantCode, PAYMENT_MODE, prn,
                amtStr, CURRENCY, date,
                r1, r2, dv, returnUrl,
                redirectUrl
        );
    }

    /**
     * Verify a Fonepay callback response after payment.
     *
     * <p>ALWAYS call this after receiving the Fonepay redirect.
     * The redirect URL parameters can be faked.
     *
     * <p>This method:
     * <ol>
     *   <li>Re-computes the HMAC-SHA512 signature from callback fields</li>
     *   <li>Compares it with the {@code DV} parameter from Fonepay</li>
     *   <li>Checks payment status is "success"</li>
     * </ol>
     *
     * <p>Only returns a successful result if BOTH signature matches
     * AND status is "success".
     *
     * <p>Response signature field order (MANDATORY per Fonepay spec):
     * {@code PRN,PID,PS,RC,UID,BC,INI,P_AMT,R_AMT}
     *
     * @param callback parsed callback parameters from Fonepay redirect
     * @return verification result with payment status
     * @throws FonepayException if signature verification fails or params are missing
     */
    public FonepayVerificationResult verifyCallback(FonepayCallbackResponse callback) {
        if (callback == null) {
            throw new FonepayException("FonepayCallbackResponse cannot be null");
        }

        validateCallbackParams(callback);

        log.debug("[NepalPay] Fonepay verifying callback | prn={} | ps={}",
                callback.prn(), callback.ps());

        // Build response signature message — field order MANDATORY per Fonepay spec:
        // PRN,PID,PS,RC,UID,BC,INI,P_AMT,R_AMT
        String message = callback.prn()  + "," + callback.pid()  + ","
                + callback.ps()   + "," + callback.rc()   + ","
                + callback.uid()  + "," + callback.bc()   + ","
                + callback.ini()  + "," + callback.pAmt() + ","
                + callback.rAmt();

        String expectedDv = generateHmacSha512Hex(message, props.secretKey())
                .toUpperCase();

        String receivedDv = callback.dv() != null
                ? callback.dv().toUpperCase()
                : "";

        if (!expectedDv.equals(receivedDv)) {
            log.error("[NepalPay] Fonepay signature MISMATCH — possible tampering! prn={}",
                    callback.prn());
            throw new FonepayException(
                    "Fonepay callback signature verification FAILED. " +
                            "Possible tampered response. PRN=" + callback.prn()
            );
        }

        boolean paymentSuccess = callback.isPaymentSuccessful();

        log.info("[NepalPay] Fonepay callback verified | prn={} | ps={} | success={}",
                callback.prn(), callback.ps(), paymentSuccess);

        return new FonepayVerificationResult(callback, paymentSuccess);
    }

    /**
     * Returns true if operating in sandbox (dev) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() {
        return props.sandbox();
    }

    /**
     * Returns the current active Fonepay gateway URL.
     *
     * @return sandbox or production gateway URL
     */
    public String gatewayUrl() {
        return gatewayUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VERIFICATION RESULT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of {@link #verifyCallback} containing verification data.
     *
     * @param callbackResponse the parsed Fonepay callback parameters
     * @param verified         true only if signature matched AND status = "success"
     */
    public record FonepayVerificationResult(
            FonepayCallbackResponse callbackResponse,
            boolean verified
    ) {
        /**
         * Returns true if payment is confirmed and safe to mark as paid.
         *
         * @return true if payment is successful
         */
        public boolean isPaymentSuccessful() {
            return verified;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate HMAC-SHA512 signature as lowercase hexadecimal.
     *
     * <p>Official Fonepay algorithm:
     * HMAC-SHA512(secretKey, message, UTF-8) → hex lowercase
     *
     * <p>This differs from eSewa which uses Base64 encoding.
     * Fonepay uses hex. Response verification uses UPPERCASE hex.
     */
    private String generateHmacSha512Hex(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac); // Java 17+ HexFormat
        } catch (Exception e) {
            throw new FonepayException(
                    "Failed to generate HMAC-SHA512 signature: " + e.getMessage(), e);
        }
    }

    /**
     * Format amount as a string suitable for Fonepay.
     * Fonepay uses double — format without unnecessary trailing zeros.
     */
    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }

    private void validateConfig() {
        if (props.merchantCode() == null || props.merchantCode().isBlank()) {
            throw new FonepayException(
                    "Fonepay merchant code not configured. " +
                            "Set nepalpay.fonepay.merchant-code in application.yml");
        }
        if (props.secretKey() == null || props.secretKey().isBlank()) {
            throw new FonepayException(
                    "Fonepay secret key not configured. " +
                            "Set nepalpay.fonepay.secret-key in application.yml");
        }
        if (props.returnUrl() == null || props.returnUrl().isBlank()) {
            throw new FonepayException(
                    "Fonepay return URL not configured. " +
                            "Set nepalpay.fonepay.return-url in application.yml");
        }
    }

    private void validateRequest(String prn, double amount, String remarks1) {
        if (prn == null || prn.isBlank()) {
            throw new FonepayException("PRN (Product Reference Number) is required");
        }
        if (prn.length() < 3 || prn.length() > 25) {
            throw new FonepayException(
                    "PRN must be between 3 and 25 characters. Got: " + prn.length());
        }
        if (amount <= 0) {
            throw new FonepayException(
                    "Amount must be greater than 0. Got: " + amount);
        }
        if (remarks1 == null || remarks1.isBlank()) {
            throw new FonepayException("remarks1 is required and cannot be blank");
        }
        if (remarks1.length() > 160) {
            throw new FonepayException(
                    "remarks1 must not exceed 160 characters. Got: " + remarks1.length());
        }
    }

    private void validateCallbackParams(FonepayCallbackResponse callback) {
        if (callback.prn() == null || callback.prn().isBlank()) {
            throw new FonepayException("Callback PRN is missing");
        }
        if (callback.dv() == null || callback.dv().isBlank()) {
            throw new FonepayException("Callback DV (signature) is missing");
        }
        if (callback.ps() == null || callback.ps().isBlank()) {
            throw new FonepayException("Callback PS (payment status) is missing");
        }
    }
}