package io.nepalpay.fonepay;

import io.micrometer.core.instrument.MeterRegistry;
import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.FonepayException;
import io.nepalpay.core.fonepay.model.FonepayCallbackResponse;
import io.nepalpay.core.fonepay.model.FonepayPaymentRequest;
import io.nepalpay.core.fonepay.model.FonepayRedirectParams;
import io.nepalpay.core.metrics.FonepayMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Fonepay Payment Gateway Client for Spring Boot 4.
 *
 * <p>Fonepay uses a <strong>URL redirect model</strong>:
 * Your backend builds signed redirect parameters, constructs the redirect
 * URL, and the frontend redirects the user directly to Fonepay.
 *
 * <p>Signature algorithm: HMAC-SHA512, hex output (not Base64 like eSewa).
 *
 * <p>This client provides:
 * <ul>
 *   <li>{@link #buildRedirectParams} — Build signed redirect URL params</li>
 *   <li>{@link #verifyCallback}      — Verify HMAC signature + check status</li>
 * </ul>
 *
 * <p><strong>Metrics:</strong>
 * If this client is constructed with a {@link MeterRegistry}, event
 * counters are recorded — redirects built, callbacks verified, signature
 * failures. No Timers — Fonepay makes no server-side HTTP calls.
 * Metrics auto-configuration is active — wired via NepalPayMetricsAutoConfiguration.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class FonepayClient {

    // ── Official Fonepay gateway URLs ─────────────────────────────────────
    private static final String SANDBOX_URL    =
            "https://dev.fonepay.com/api/merchantRequest";
    private static final String PRODUCTION_URL =
            "https://fonepay.com/api/merchantRequest";

    // ── Constants ─────────────────────────────────────────────────────────
    private static final String PAYMENT_MODE   = "P";
    private static final String CURRENCY       = "NPR";
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.FonepayProperties props;
    private final String gatewayUrl;

    /**
     * Optional Micrometer metrics — null when Actuator not on classpath.
     * All usages guarded with null check — no NPE possible.
     */
    private final FonepayMetrics metrics;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — no metrics.
     * Used by auto-configuration when Actuator is absent.
     *
     * @param props Fonepay properties from application.yml
     */
    public FonepayClient(NepalPayProperties.FonepayProperties props) {
        this(props,
                props.sandbox() ? SANDBOX_URL : PRODUCTION_URL,
                null);
    }

    /**
     * Test constructor — custom gateway URL, no metrics.
     *
     * <p>Used in tests to override the Fonepay gateway URL.
     *
     * <p><strong>NOTE (CodeRabbit fix):</strong>
     * A separate {@code FonepayClient(props, MeterRegistry)} overload
     * was removed because it was ambiguous with this constructor at the
     * call site when {@code null} was passed. Use the 3-arg constructor
     * {@link #FonepayClient(NepalPayProperties.FonepayProperties,
     * String, MeterRegistry)} to inject a {@link MeterRegistry} instead.
     *
     * @param props              Fonepay properties
     * @param gatewayUrlOverride Custom gateway URL for testing
     */
    public FonepayClient(
            NepalPayProperties.FonepayProperties props,
            String gatewayUrlOverride) {
        this(props, gatewayUrlOverride, null);
    }

    /**
     * Full constructor — custom gateway URL + optional metrics.
     *
     * <p>Used by {@code NepalPayMetricsAutoConfiguration} to inject a
     * {@link MeterRegistry} when Actuator is on the classpath.
     * Exposed as {@code public} to avoid constructor ambiguity — passing
     * {@code null} for {@code meterRegistry} is safe and disables metrics.
     *
     * <p>This replaces the removed
     * {@code FonepayClient(props, MeterRegistry)} 2-arg overload which
     * was ambiguous with the String test constructor when {@code null}
     * was passed.
     *
     * @param props              Fonepay properties from application.yml
     * @param gatewayUrlOverride Gateway URL — pass
     *   {@code props.sandbox() ? SANDBOX_URL : PRODUCTION_URL} for
     *   production use, or a custom URL in tests
     * @param meterRegistry      Micrometer registry — null = no metrics
     */
    public FonepayClient(
            NepalPayProperties.FonepayProperties props,
            String gatewayUrlOverride,
            MeterRegistry meterRegistry) {

        this.props      = props;
        this.gatewayUrl = gatewayUrlOverride;
        this.metrics    = (meterRegistry != null)
                ? new FonepayMetrics(meterRegistry, props.sandbox())
                : null;

        log.info("[NepalPay] FonepayClient initialized | mode={}" +
                        " | merchantCode={} | metrics={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                props.merchantCode(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build signed Fonepay redirect parameters from a request object.
     *
     * @param request payment request
     * @return signed redirect params with full redirect URL
     * @throws FonepayException if validation fails or signature generation fails
     */
    public FonepayRedirectParams buildRedirectParams(
            FonepayPaymentRequest request) {
        return buildRedirectParams(
                request.prn(),
                request.amount(),
                request.remarks1(),
                request.remarks2() != null ? request.remarks2() : "");
    }

    /**
     * Build signed Fonepay redirect parameters.
     *
     * <p>Signature algorithm (field order MANDATORY per Fonepay docs):
     * <pre>
     * Concatenate: PID,MD,PRN,AMT,CRN,DT,R1,R2,RU
     * Hash with:   HMAC-SHA512(secretKey, message, UTF-8)
     * Output:      lowercase hexadecimal
     * </pre>
     *
     * <p>Records {@code nepalpay.fonepay.redirect.built} counter on success.
     *
     * @param prn      Unique PRN (3-25 chars, store in DB!)
     * @param amount   Amount in NPR as a double
     * @param remarks1 Payment description (max 160 chars)
     * @param remarks2 Additional remarks (max 50 chars)
     * @return signed redirect params with full redirect URL
     * @throws FonepayException if config is invalid or signature fails
     */
    public FonepayRedirectParams buildRedirectParams(
            String prn, double amount,
            String remarks1, String remarks2) {

        validateConfig();
        validateRequest(prn, amount, remarks1);

        String merchantCode = props.merchantCode();
        String returnUrl    = props.returnUrl();
        String date         = LocalDate.now().format(DATE_FORMAT);
        String amtStr       = formatAmount(amount);
        String r1           = remarks1 != null ? remarks1 : "Payment";
        String r2           = remarks2 != null ? remarks2 : "";

        // Field order MANDATORY per Fonepay spec: PID,MD,PRN,AMT,CRN,DT,R1,R2,RU
        String message = merchantCode + "," + PAYMENT_MODE + "," + prn + ","
                + amtStr + "," + CURRENCY + "," + date + ","
                + r1 + "," + r2 + "," + returnUrl;

        String dv = generateHmacSha512Hex(message, props.secretKey());

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

        if (metrics != null) {
            metrics.incrementRedirectBuilt();
        }

        return new FonepayRedirectParams(
                merchantCode, PAYMENT_MODE, prn,
                amtStr, CURRENCY, date,
                r1, r2, dv, returnUrl, redirectUrl);
    }

    /**
     * Verify a Fonepay callback response after payment.
     *
     * <p>This method:
     * <ol>
     *   <li>Re-computes HMAC-SHA512 from callback fields</li>
     *   <li>Compares with the {@code DV} parameter from Fonepay using
     *       constant-time comparison to prevent timing attacks</li>
     *   <li>Checks payment status is "success" — only AFTER HMAC passes</li>
     * </ol>
     *
     * <p>Records counters when Micrometer available:
     * <ul>
     *   <li>{@code nepalpay.fonepay.callback.signature.failed} on mismatch</li>
     *   <li>{@code nepalpay.fonepay.callback.verified{status=success}}</li>
     *   <li>{@code nepalpay.fonepay.callback.verified{status=failed}}</li>
     * </ul>
     *
     * @param callback parsed callback parameters from Fonepay redirect
     * @return verification result with payment status
     * @throws FonepayException if signature verification fails or params missing
     */
    public FonepayVerificationResult verifyCallback(
            FonepayCallbackResponse callback) {

        if (callback == null) {
            throw new FonepayException(
                    "FonepayCallbackResponse cannot be null");
        }

        validateCallbackParams(callback);

        log.debug("[NepalPay] Fonepay verifying callback | prn={} | ps={}",
                callback.prn(), callback.ps());

        // Field order MANDATORY: PRN,PID,PS,RC,UID,BC,INI,P_AMT,R_AMT
        String message = callback.prn()  + "," + callback.pid()  + ","
                + callback.ps()   + "," + callback.rc()   + ","
                + callback.uid()  + "," + callback.bc()   + ","
                + callback.ini()  + "," + callback.pAmt() + ","
                + callback.rAmt();

        byte[] expectedBytes = generateHmacSha512Hex(
                message, props.secretKey())
                .toUpperCase()
                .getBytes(StandardCharsets.UTF_8);
        byte[] receivedBytes = (callback.dv() != null
                ? callback.dv().toUpperCase() : "")
                .getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expectedBytes, receivedBytes)) {
            log.error("[NepalPay] Fonepay SIGNATURE MISMATCH — " +
                    "possible tampering! prn={}", callback.prn());

            if (metrics != null) {
                metrics.incrementSignatureFailed();
            }

            throw new FonepayException(
                    "Fonepay callback signature verification FAILED. " +
                            "Possible tampered response. PRN=" + callback.prn());
        }

        boolean paymentSuccess = callback.paymentStatus().isSuccess();

        log.info("[NepalPay] Fonepay callback verified | prn={}" +
                        " | ps={} | success={}",
                callback.prn(), callback.ps(), paymentSuccess);

        if (metrics != null) {
            if (paymentSuccess) {
                metrics.incrementCallbackSuccess();
            } else {
                metrics.incrementCallbackFailed();
            }
        }

        return new FonepayVerificationResult(callback, paymentSuccess);
    }

    /**
     * Returns true if operating in sandbox (dev) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() { return props.sandbox(); }

    /**
     * Returns the current active Fonepay gateway URL.
     *
     * @return sandbox or production gateway URL
     */
    public String gatewayUrl() { return gatewayUrl; }

    // ─────────────────────────────────────────────────────────────────────
    // VERIFICATION RESULT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Result of {@link #verifyCallback}.
     *
     * @param callbackResponse the parsed Fonepay callback parameters
     * @param verified         true only if HMAC matched AND PS = "success"
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

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generate HMAC-SHA512 signature as lowercase hexadecimal.
     * Fonepay uses hex (not Base64 like eSewa).
     * Response DV verification uses UPPERCASE hex.
     */
    private String generateHmacSha512Hex(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(
                    message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new FonepayException(
                    "Failed to generate HMAC-SHA512 signature: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Format amount as a string suitable for Fonepay.
     * Whole numbers have no decimal point — 100.0 → "100".
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

    private void validateRequest(
            String prn, double amount, String remarks1) {

        if (prn == null || prn.isBlank()) {
            throw new FonepayException(
                    "PRN (Product Reference Number) is required");
        }
        if (prn.length() < 3 || prn.length() > 25) {
            throw new FonepayException(
                    "PRN must be between 3 and 25 characters. Got: "
                            + prn.length());
        }
        if (amount <= 0) {
            throw new FonepayException(
                    "Amount must be greater than 0. Got: " + amount);
        }
        if (remarks1 == null || remarks1.isBlank()) {
            throw new FonepayException(
                    "remarks1 is required and cannot be blank");
        }
        if (remarks1.length() > 160) {
            throw new FonepayException(
                    "remarks1 must not exceed 160 characters. Got: "
                            + remarks1.length());
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
            throw new FonepayException(
                    "Callback PS (payment status) is missing");
        }
    }
}