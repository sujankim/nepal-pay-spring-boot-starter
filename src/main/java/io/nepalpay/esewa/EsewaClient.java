package io.nepalpay.esewa;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.esewa.exception.EsewaException;
import io.nepalpay.esewa.model.EsewaCallbackData;
import io.nepalpay.esewa.model.EsewaFormPayload;
import io.nepalpay.esewa.model.EsewaStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * eSewa Payment Gateway Client for Spring Boot 4.1.0.
 *
 * <p>eSewa uses a FORM-SUBMISSION model (not API-first like Khalti).
 *
 * <p>This client provides:
 * <ul>
 *   <li>{@link #buildFormPayload} — Build HMAC-signed form data to return to frontend</li>
 *   <li>{@link #verifyCallback}  — Decode and verify Base64 callback from eSewa</li>
 *   <li>{@link #checkStatus}     — Direct server-side status API confirmation</li>
 * </ul>
 *
 * <p>Official eSewa docs: https://developer.esewa.com.np/pages/Epay-V2
 *
 * <p>Complete integration flow:
 * <pre>{@code
 * // Step 1: Build signed form payload
 * EsewaFormPayload payload = esewaClient.buildFormPayload(
 *     new BigDecimal("100.00"),
 *     "ORD-001"
 * );
 * // Return payload to frontend.
 * // Frontend POSTs form fields to payload.formActionUrl()
 *
 * // Step 2: Receive callback
 * // eSewa redirects to: yourSuccessUrl?data=BASE64_JSON
 * EsewaVerificationResult result = esewaClient.verifyCallback(encodedData);
 * if (result.isPaymentSuccessful()) {
 *     // Safe to mark order as paid
 * }
 * }</pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class EsewaClient {

    // Official eSewa form submission URLs
    private static final String SANDBOX_FORM_URL    = "https://rc-epay.esewa.com.np/api/epay/main/v2/form";
    private static final String PRODUCTION_FORM_URL = "https://epay.esewa.com.np/api/epay/main/v2/form";

    // Official eSewa status API base URLs
    private static final String SANDBOX_STATUS_BASE_URL    = "https://rc.esewa.com.np";
    private static final String PRODUCTION_STATUS_BASE_URL = "https://esewa.com.np";
    private static final String STATUS_PATH                = "/api/epay/transaction/status/";

    // HMAC-SHA256 — official eSewa signature algorithm
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    // Fixed signed field names for initiation — ORDER IS MANDATORY per official docs
    private static final String SIGNED_FIELD_NAMES = "total_amount,transaction_uuid,product_code";

    private final NepalPayProperties.EsewaProperties props;
    private final RestClient restClient;
    private final String formActionUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by {@link io.nepalpay.autoconfigure.NepalPayAutoConfiguration}.
     *
     * <p>Status API base URL is automatically determined from {@code nepalpay.esewa.sandbox}:
     * <ul>
     *   <li>sandbox=true  → https://rc.esewa.com.np</li>
     *   <li>sandbox=false → https://esewa.com.np</li>
     * </ul>
     *
     * @param props             eSewa properties from application.yml
     * @param restClientBuilder Spring Boot's RestClient builder
     */
    public EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder) {

        this(
                props,
                restClientBuilder,
                props.sandbox() ? SANDBOX_STATUS_BASE_URL : PRODUCTION_STATUS_BASE_URL
        );
    }

    /**
     * Test constructor — allows injecting a custom status API base URL.
     *
     * <p>Used in tests to point the status API calls at a {@code MockWebServer}
     * instead of the real eSewa API.
     *
     * <p>Example test usage:
     * <pre>{@code
     * EsewaClient client = new EsewaClient(
     *     props,
     *     RestClient.builder(),
     *     mockWebServer.url("/").toString()
     * );
     * }</pre>
     *
     * @param props                  eSewa properties
     * @param restClientBuilder      RestClient builder
     * @param statusBaseUrlOverride  Custom base URL for status API (e.g. MockWebServer URL)
     */
    public EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder,
            String statusBaseUrlOverride) {

        this.props         = props;
        this.formActionUrl = props.sandbox() ? SANDBOX_FORM_URL : PRODUCTION_FORM_URL;

        this.restClient = restClientBuilder
                .baseUrl(statusBaseUrlOverride)
                .build();

        log.info("[NepalPay] EsewaClient initialized | mode={} | productCode={} | statusUrl={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                props.productCode(),
                statusBaseUrlOverride);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed eSewa form payload to return to the frontend.
     *
     * <p>The frontend renders a form with these fields and POSTs it
     * to {@link EsewaFormPayload#formActionUrl()}.
     *
     * <p>Amounts are in NPR (Nepali Rupees) directly — NOT paisa like Khalti.
     *
     * @param amount            Base transaction amount in NPR
     * @param taxAmount         Tax amount in NPR (pass {@code BigDecimal.ZERO} if none)
     * @param transactionUuid   Your unique transaction ID (store in DB!)
     * @param serviceCharge     Service charge in NPR ({@code BigDecimal.ZERO} if none)
     * @param deliveryCharge    Delivery charge in NPR ({@code BigDecimal.ZERO} if none)
     * @return Signed form payload to return to the frontend
     * @throws EsewaException if signature generation fails or config is missing
     */
    public EsewaFormPayload buildFormPayload(
            BigDecimal amount,
            BigDecimal taxAmount,
            String transactionUuid,
            BigDecimal serviceCharge,
            BigDecimal deliveryCharge) {

        validateBuildRequest(amount, transactionUuid);

        BigDecimal tax      = (taxAmount      != null) ? taxAmount      : BigDecimal.ZERO;
        BigDecimal service  = (serviceCharge  != null) ? serviceCharge  : BigDecimal.ZERO;
        BigDecimal delivery = (deliveryCharge != null) ? deliveryCharge : BigDecimal.ZERO;

        BigDecimal totalAmount = amount
                .add(tax)
                .add(service)
                .add(delivery)
                .setScale(2, RoundingMode.HALF_UP);

        String amountStr   = formatAmount(amount);
        String taxStr      = formatAmount(tax);
        String totalStr    = formatAmount(totalAmount);
        String serviceStr  = formatAmount(service);
        String deliveryStr = formatAmount(delivery);

        // Generate HMAC-SHA256 signature
        // Official format: "total_amount=X,transaction_uuid=Y,product_code=Z"
        // WARNING: Field order is MANDATORY — wrong order = signature mismatch
        String message   = buildSignatureMessage(totalStr, transactionUuid, props.productCode());
        String signature = generateHmacSignature(message, props.secretKey());

        String successUrl = props.successUrl();
        String failureUrl = props.failureUrl();

        if (successUrl == null || successUrl.isBlank()) {
            throw new EsewaException(
                    "successUrl is required. Set nepalpay.esewa.success-url in application.yml");
        }
        if (failureUrl == null || failureUrl.isBlank()) {
            throw new EsewaException(
                    "failureUrl is required. Set nepalpay.esewa.failure-url in application.yml");
        }

        log.debug("[NepalPay] eSewa form payload built | uuid={} | total={}",
                transactionUuid, totalStr);

        return new EsewaFormPayload(
                amountStr,
                taxStr,
                totalStr,
                transactionUuid,
                props.productCode(),
                serviceStr,
                deliveryStr,
                successUrl,
                failureUrl,
                SIGNED_FIELD_NAMES,
                signature,
                formActionUrl
        );
    }

    /**
     * Simplified overload — only amount and transactionUuid required.
     * Tax, service charge, and delivery charge default to zero.
     *
     * @param amount          Transaction amount in NPR
     * @param transactionUuid Your unique transaction ID (store in DB!)
     * @return Signed form payload to return to the frontend
     */
    public EsewaFormPayload buildFormPayload(BigDecimal amount, String transactionUuid) {
        return buildFormPayload(
                amount,
                BigDecimal.ZERO,
                transactionUuid,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    /**
     * Verify eSewa callback after payment redirect.
     *
     * <p>When eSewa redirects to your successUrl, it appends:
     * {@code ?data=BASE64_ENCODED_JSON}
     *
     * <p>This method performs three steps:
     * <ol>
     *   <li>Decodes the Base64 data parameter into {@link EsewaCallbackData}</li>
     *   <li>Re-computes HMAC signature and verifies it matches (tamper protection)</li>
     *   <li>Calls the eSewa status API for final server-side confirmation</li>
     * </ol>
     *
     * @param encodedData The raw Base64 "data" query parameter from eSewa redirect
     * @return Verification result with callback data and status response
     * @throws EsewaException if decoding fails, signature mismatches, or status API fails
     */
    public EsewaVerificationResult verifyCallback(String encodedData) {
        if (encodedData == null || encodedData.isBlank()) {
            throw new EsewaException("eSewa callback data cannot be null or blank");
        }

        log.debug("[NepalPay] eSewa verifying callback");

        EsewaCallbackData callbackData = decodeCallbackData(encodedData);
        verifyCallbackSignature(callbackData);

        EsewaStatusResponse statusResponse = checkStatus(
                callbackData.transactionUuid(),
                callbackData.totalAmount()
        );

        boolean verified = statusResponse.isPaymentSuccessful();

        log.info("[NepalPay] eSewa callback verified | uuid={} | status={} | verified={}",
                callbackData.transactionUuid(),
                statusResponse.status(),
                verified);

        return new EsewaVerificationResult(callbackData, statusResponse, verified);
    }

    /**
     * Directly check eSewa transaction status via the status API.
     *
     * <p>Useful when you want to poll status independently
     * without going through the callback flow.
     *
     * @param transactionUuid Your original transaction UUID
     * @param totalAmount     The exact total amount (must match original)
     * @return Status response from eSewa
     * @throws EsewaException if the API call fails
     */
    public EsewaStatusResponse checkStatus(String transactionUuid, String totalAmount) {
        if (transactionUuid == null || transactionUuid.isBlank()) {
            throw new EsewaException("transactionUuid cannot be null or blank");
        }
        if (totalAmount == null || totalAmount.isBlank()) {
            throw new EsewaException("totalAmount cannot be null or blank");
        }

        log.debug("[NepalPay] eSewa status check | uuid={}", transactionUuid);

        try {
            EsewaStatusResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(STATUS_PATH)
                            .queryParam("product_code",     props.productCode())
                            .queryParam("total_amount",     totalAmount)
                            .queryParam("transaction_uuid", transactionUuid)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error("[NepalPay] eSewa status check 4xx | uuid={} | body={}",
                                transactionUuid, body);
                        throw new EsewaException(
                                "eSewa status check failed — check product_code or transaction_uuid",
                                res.getStatusCode().value(),
                                body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] eSewa status API server error | uuid={}",
                                transactionUuid);
                        throw new EsewaException(
                                "eSewa server error during status check — try again",
                                res.getStatusCode().value(),
                                null);
                    })
                    .body(EsewaStatusResponse.class);

            if (response == null) {
                throw new EsewaException(
                        "eSewa returned empty status response for uuid=" + transactionUuid);
            }

            log.info("[NepalPay] eSewa status result | uuid={} | status={} | refId={}",
                    transactionUuid, response.status(), response.refId());

            return response;

        } catch (EsewaException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during eSewa status check | uuid={}",
                    transactionUuid, e);
            throw new EsewaException(
                    "Unexpected error during eSewa status check: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a unique transaction UUID suitable for eSewa.
     *
     * <p>eSewa requires alphanumeric characters and hyphens only.
     *
     * @return A unique transaction UUID string
     */
    public static String generateTransactionUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the eSewa form action URL (sandbox or production).
     *
     * @return form action URL
     */
    public String formActionUrl() {
        return formActionUrl;
    }

    /**
     * Returns true if operating in sandbox (test) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() {
        return props.sandbox();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VERIFICATION RESULT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of {@link #verifyCallback} containing all verification data.
     *
     * @param callbackData   Decoded data from eSewa redirect
     * @param statusResponse Response from eSewa status API
     * @param verified       True only if signature matched AND status is COMPLETE
     */
    public record EsewaVerificationResult(
            EsewaCallbackData callbackData,
            EsewaStatusResponse statusResponse,
            boolean verified
    ) {
        /**
         * Returns true if the payment is confirmed and safe to mark as paid.
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

    private EsewaCallbackData decodeCallbackData(String encodedData) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedData.trim());
            String jsonString   = new String(decodedBytes, StandardCharsets.UTF_8);

            log.debug("[NepalPay] eSewa decoded callback JSON: {}", jsonString);

            JsonMapper jsonMapper = JsonMapper.builder().build();
            return jsonMapper.readValue(jsonString, EsewaCallbackData.class);

        } catch (IllegalArgumentException e) {
            throw new EsewaException(
                    "Failed to decode eSewa Base64 callback data. " +
                            "Pass the raw 'data' query parameter from the redirect URL.", e);
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to parse eSewa callback JSON: " + e.getMessage(), e);
        }
    }

    private void verifyCallbackSignature(EsewaCallbackData data) {
        try {
            String signedFields = data.signedFieldNames();
            if (signedFields == null || signedFields.isBlank()) {
                throw new EsewaException("signed_field_names is missing from eSewa callback");
            }

            StringBuilder messageBuilder = new StringBuilder();
            String[] fields = signedFields.split(",");
            for (int i = 0; i < fields.length; i++) {
                String field = fields[i].trim();
                String value = getCallbackFieldValue(data, field);
                messageBuilder.append(field).append("=").append(value);
                if (i < fields.length - 1) {
                    messageBuilder.append(",");
                }
            }

            String expectedSignature = generateHmacSignature(
                    messageBuilder.toString(),
                    props.secretKey()
            );

            if (!expectedSignature.equals(data.signature())) {
                log.error("[NepalPay] eSewa signature MISMATCH — possible tampering! uuid={}",
                        data.transactionUuid());
                throw new EsewaException(
                        "eSewa callback signature verification FAILED. " +
                                "Possible tampered response. uuid=" + data.transactionUuid()
                );
            }

            log.debug("[NepalPay] eSewa callback signature OK | uuid={}",
                    data.transactionUuid());

        } catch (EsewaException e) {
            throw e;
        } catch (Exception e) {
            throw new EsewaException(
                    "Error during eSewa signature verification: " + e.getMessage(), e);
        }
    }

    private String generateHmacSignature(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);

        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to generate HMAC-SHA256 signature: " + e.getMessage(), e);
        }
    }

    private String buildSignatureMessage(
            String totalAmount, String transactionUuid, String productCode) {
        return "total_amount=" + totalAmount
                + ",transaction_uuid=" + transactionUuid
                + ",product_code=" + productCode;
    }

    private String getCallbackFieldValue(EsewaCallbackData data, String fieldName) {
        return switch (fieldName.trim()) {
            case "transaction_code"   -> nullToEmpty(data.transactionCode());
            case "status"             -> nullToEmpty(data.status());
            case "total_amount"       -> nullToEmpty(data.totalAmount());
            case "transaction_uuid"   -> nullToEmpty(data.transactionUuid());
            case "product_code"       -> nullToEmpty(data.productCode());
            case "signed_field_names" -> nullToEmpty(data.signedFieldNames());
            default -> {
                log.warn("[NepalPay] Unknown signed field in eSewa callback: {}", fieldName);
                yield "";
            }
        };
    }

    private void validateBuildRequest(BigDecimal amount, String transactionUuid) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new EsewaException(
                    "Amount must be greater than 0 NPR. Got: " + amount);
        }
        if (transactionUuid == null || transactionUuid.isBlank()) {
            throw new EsewaException("transactionUuid is required and cannot be blank");
        }
        if (props.secretKey() == null || props.secretKey().isBlank()) {
            throw new EsewaException(
                    "eSewa secret key not configured. " +
                            "Set nepalpay.esewa.secret-key in application.yml");
        }
        if (props.productCode() == null || props.productCode().isBlank()) {
            throw new EsewaException(
                    "eSewa product code not configured. " +
                            "Set nepalpay.esewa.product-code in application.yml. " +
                            "Sandbox: EPAYTEST | Production: your merchant code");
        }
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String readBodySafely(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}