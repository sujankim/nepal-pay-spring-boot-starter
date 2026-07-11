package io.nepalpay.esewa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.esewa.model.EsewaCallbackData;
import io.nepalpay.core.esewa.model.EsewaFormPayload;
import io.nepalpay.core.esewa.model.EsewaStatusResponse;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.metrics.EsewaMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * eSewa Payment Gateway Client — Spring Boot 3.
 *
 * <p>Uses Jackson 2 ({@code com.fasterxml.jackson.databind.ObjectMapper})
 * for Base64 callback decoding.
 *
 * <p>This client provides:
 * <ul>
 *   <li>{@link #buildFormPayload} — Build HMAC-signed form payload</li>
 *   <li>{@link #verifyCallback}  — Decode, verify, and confirm callback</li>
 *   <li>{@link #checkStatus}     — Direct server-side status check</li>
 * </ul>
 *
 * <p><strong>Metrics:</strong>
 * When {@code spring-boot-starter-actuator} is on the classpath and a
 * {@link MeterRegistry} is injected, all HTTP operations are timed and
 * signature failures are counted automatically. If no {@link MeterRegistry}
 * is available, all metric recording is silently skipped.
 *
 * <p>Official eSewa docs: https://developer.esewa.com.np/pages/Epay-V2
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class EsewaClient {

    // ── Official eSewa URLs ───────────────────────────────────────────────
    private static final String SANDBOX_FORM_URL        =
            "https://rc-epay.esewa.com.np/api/epay/main/v2/form";
    private static final String PRODUCTION_FORM_URL     =
            "https://epay.esewa.com.np/api/epay/main/v2/form";
    private static final String SANDBOX_STATUS_BASE_URL =
            "https://rc.esewa.com.np";
    private static final String PROD_STATUS_BASE_URL    =
            "https://esewa.com.np";
    private static final String STATUS_PATH             =
            "/api/epay/transaction/status/";

    // ── Signature ─────────────────────────────────────────────────────────
    private static final String HMAC_ALGORITHM     = "HmacSHA256";
    private static final String SIGNED_FIELD_NAMES =
            "total_amount,transaction_uuid,product_code";

    // ── Shared singleton ObjectMapper — thread-safe after construction ─────
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.EsewaProperties props;
    private final RestClient      restClient;
    private final String          formActionUrl;
    private final RetryProperties retryProps;

    /**
     * Optional Micrometer metrics — null when Actuator not on classpath.
     * All usages guarded with null check — no NPE possible.
     */
    private final EsewaMetrics metrics;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     * No metrics.
     */
    public EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder) {

        this(props, restClientBuilder,
                props.sandbox()
                        ? SANDBOX_STATUS_BASE_URL : PROD_STATUS_BASE_URL,
                null);
    }

    /**
     * Production constructor WITH metrics.
     * Used by auto-configuration when Actuator + MeterRegistry are available.
     *
     * @param props             eSewa properties from application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     * @param meterRegistry     Micrometer registry — may be null
     */
    public EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry) {

        this(props, restClientBuilder,
                props.sandbox()
                        ? SANDBOX_STATUS_BASE_URL : PROD_STATUS_BASE_URL,
                meterRegistry);
    }

    /**
     * Test constructor — allows injecting a custom status API base URL.
     * No metrics.
     */
    public EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder,
            String statusBaseUrlOverride) {

        this(props, restClientBuilder, statusBaseUrlOverride, null);
    }

    /**
     * Core private constructor — single point of initialization.
     */
    private EsewaClient(
            NepalPayProperties.EsewaProperties props,
            RestClient.Builder restClientBuilder,
            String statusBaseUrlOverride,
            MeterRegistry meterRegistry) {

        this.props         = props;
        this.formActionUrl = props.sandbox()
                ? SANDBOX_FORM_URL : PRODUCTION_FORM_URL;
        this.retryProps    = props.retryOrDefault();

        // ── Metrics — optional ────────────────────────────────────────────
        this.metrics = (meterRegistry != null)
                ? new EsewaMetrics(meterRegistry, props.sandbox())
                : null;

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));

        this.restClient = restClientBuilder
                .baseUrl(statusBaseUrlOverride)
                .requestFactory(factory)
                .build();

        log.info("[NepalPay] EsewaClient initialized | mode={} | productCode={}" +
                        " | statusUrl={} | timeout={}s | retry={} | metrics={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                props.productCode(),
                statusBaseUrlOverride,
                props.timeoutSeconds(),
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build a signed eSewa form payload — full overload with all charges.
     *
     * @param amount          Base amount in NPR
     * @param taxAmount       Tax in NPR ({@code null} = zero)
     * @param transactionUuid Your unique transaction ID (store in DB!)
     * @param serviceCharge   Service charge in NPR ({@code null} = zero)
     * @param deliveryCharge  Delivery charge in NPR ({@code null} = zero)
     * @return Signed form payload to return to frontend
     * @throws EsewaException if signature generation fails
     */
    public EsewaFormPayload buildFormPayload(
            BigDecimal amount,
            BigDecimal taxAmount,
            String transactionUuid,
            BigDecimal serviceCharge,
            BigDecimal deliveryCharge) {

        validateBuildRequest(amount, transactionUuid);

        BigDecimal tax      = taxAmount      != null ? taxAmount      : BigDecimal.ZERO;
        BigDecimal service  = serviceCharge  != null ? serviceCharge  : BigDecimal.ZERO;
        BigDecimal delivery = deliveryCharge != null ? deliveryCharge : BigDecimal.ZERO;

        BigDecimal total = amount.add(tax).add(service).add(delivery)
                .setScale(2, RoundingMode.HALF_UP);

        String amountStr   = formatAmount(amount);
        String taxStr      = formatAmount(tax);
        String totalStr    = formatAmount(total);
        String serviceStr  = formatAmount(service);
        String deliveryStr = formatAmount(delivery);

        String message   = buildSignatureMessage(
                totalStr, transactionUuid, props.productCode());
        String signature = generateHmacSignature(message, props.secretKey());

        if (props.successUrl() == null || props.successUrl().isBlank()) {
            throw new EsewaException(
                    "successUrl is required. " +
                            "Set nepalpay.esewa.success-url in application.yml");
        }
        if (props.failureUrl() == null || props.failureUrl().isBlank()) {
            throw new EsewaException(
                    "failureUrl is required. " +
                            "Set nepalpay.esewa.failure-url in application.yml");
        }

        log.debug("[NepalPay] eSewa form payload built | uuid={} | total={}",
                transactionUuid, totalStr);

        return new EsewaFormPayload(
                amountStr, taxStr, totalStr, transactionUuid,
                props.productCode(), serviceStr, deliveryStr,
                props.successUrl(), props.failureUrl(),
                SIGNED_FIELD_NAMES, signature, formActionUrl);
    }

    /**
     * Build a signed eSewa form payload — simple overload.
     * Tax, service charge, and delivery charge default to zero.
     */
    public EsewaFormPayload buildFormPayload(
            BigDecimal amount, String transactionUuid) {
        return buildFormPayload(amount, BigDecimal.ZERO, transactionUuid,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Verify eSewa callback after payment redirect.
     *
     * <p>Performs three steps automatically:
     * <ol>
     *   <li>Decodes Base64 callback data</li>
     *   <li>Verifies HMAC-SHA256 signature</li>
     *   <li>Calls eSewa status API (with retry if configured)</li>
     * </ol>
     *
     * <p>Records {@code nepalpay.esewa.callback.verify.duration} timer.
     * Records {@code nepalpay.esewa.callback.signature.failed} counter
     * when HMAC verification fails.
     *
     * @param encodedData Base64 "data" param from eSewa redirect
     * @return Verification result
     * @throws EsewaException if decoding, signature, or status check fails
     */
    public EsewaVerificationResult verifyCallback(String encodedData) {
        if (encodedData == null || encodedData.isBlank()) {
            throw new EsewaException(
                    "eSewa callback data cannot be null or blank");
        }

        log.debug("[NepalPay] eSewa verifying callback");

        // ✅ Wrap verifyCallback with metrics timer if available
        if (metrics != null) {
            return metrics.recordVerify(() -> doVerifyCallback(encodedData));
        }
        return doVerifyCallback(encodedData);
    }

    /**
     * Directly check eSewa transaction status via the status API.
     *
     * <p>Records {@code nepalpay.esewa.status.check.duration} timer.
     *
     * @param transactionUuid Your original transaction UUID
     * @param totalAmount     The exact total amount (must match original)
     * @return Status response from eSewa
     * @throws EsewaException if the API call fails
     */
    public EsewaStatusResponse checkStatus(
            String transactionUuid, String totalAmount) {

        if (transactionUuid == null || transactionUuid.isBlank()) {
            throw new EsewaException("transactionUuid cannot be null or blank");
        }
        if (totalAmount == null || totalAmount.isBlank()) {
            throw new EsewaException("totalAmount cannot be null or blank");
        }

        log.debug("[NepalPay] eSewa status check | uuid={}", transactionUuid);

        // ✅ Wrap checkStatus with metrics timer if available
        if (metrics != null) {
            return metrics.recordStatus(
                    () -> doCheckStatus(transactionUuid, totalAmount));
        }
        return doCheckStatus(transactionUuid, totalAmount);
    }

    /**
     * Generate a unique transaction UUID suitable for eSewa.
     *
     * @return UUID string
     */
    public static String generateTransactionUuid() {
        return UUID.randomUUID().toString();
    }

    /** @return form action URL (sandbox or production) */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if sandbox mode is active */
    public boolean isSandbox() { return props.sandbox(); }

    // ─────────────────────────────────────────────────────────────────────
    // VERIFICATION RESULT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Result of {@link #verifyCallback}.
     *
     * @param callbackData   Decoded data from eSewa redirect
     * @param statusResponse Response from eSewa status API
     * @param verified       True only if signature matched AND status COMPLETE
     */
    public record EsewaVerificationResult(
            EsewaCallbackData callbackData,
            EsewaStatusResponse statusResponse,
            boolean verified
    ) {
        /** @return true if payment is confirmed and safe to mark as paid */
        public boolean isPaymentSuccessful() { return verified; }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — HTTP execution methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inner verifyCallback logic — wrapped by metrics timer in public method.
     */
    private EsewaVerificationResult doVerifyCallback(String encodedData) {
        EsewaCallbackData callbackData = decodeCallbackData(encodedData);
        verifyCallbackSignature(callbackData);

        EsewaStatusResponse statusResponse =
                doCheckStatus(
                        callbackData.transactionUuid(),
                        callbackData.totalAmount());

        boolean verified = statusResponse.isPaymentSuccessful();

        log.info("[NepalPay] eSewa callback verified | uuid={}" +
                        " | status={} | verified={}",
                callbackData.transactionUuid(),
                statusResponse.status(), verified);

        return new EsewaVerificationResult(callbackData, statusResponse, verified);
    }

    /**
     * Inner checkStatus logic — wrapped by metrics timer in public method.
     * Also called directly from doVerifyCallback (already timed by verify timer).
     */
    private EsewaStatusResponse doCheckStatus(
            String transactionUuid, String totalAmount) {

        return executeWithRetry("eSewa status check",
                metrics != null ? metrics::incrementStatusRetry : null,
                () -> {
                    EsewaStatusResponse response = restClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path(STATUS_PATH)
                                    .queryParam("product_code",
                                            props.productCode())
                                    .queryParam("total_amount",
                                            totalAmount)
                                    .queryParam("transaction_uuid",
                                            transactionUuid)
                                    .build())
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError,
                                    (req, res) -> {
                                        String body = readBodySafely(res);
                                        log.error("[NepalPay] eSewa status" +
                                                        " 4xx | uuid={} | body={}",
                                                transactionUuid, body);
                                        throw new EsewaException(
                                                "eSewa status check failed — " +
                                                        "check product_code or uuid",
                                                res.getStatusCode().value(),
                                                body);
                                    })
                            .onStatus(HttpStatusCode::is5xxServerError,
                                    (req, res) -> {
                                        log.error("[NepalPay] eSewa status" +
                                                        " 5xx | uuid={}",
                                                transactionUuid);
                                        throw new EsewaException(
                                                "eSewa server error during" +
                                                        " status check — try again",
                                                res.getStatusCode().value(),
                                                null);
                                    })
                            .body(EsewaStatusResponse.class);

                    if (response == null) {
                        throw new EsewaException(
                                "eSewa returned empty status response" +
                                        " for uuid=" + transactionUuid);
                    }

                    log.info("[NepalPay] eSewa status result | uuid={}" +
                                    " | status={} | refId={}",
                            transactionUuid,
                            response.status(), response.refId());
                    return response;
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Retry
    // ─────────────────────────────────────────────────────────────────────

    private <T> T executeWithRetry(
            String operationName,
            Runnable retryIncrement,
            Supplier<T> operation) {

        if (!retryProps.isActive()) {
            return operation.get();
        }

        int  attempt = 0;
        long delayMs = retryProps.initialDelayMs();

        while (true) {
            try {
                return operation.get();

            } catch (EsewaException e) {
                if (e.httpStatus() >= 400 && e.httpStatus() < 500) {
                    throw e;
                }

                attempt++;

                if (attempt > retryProps.maxAttempts()) {
                    log.error("[NepalPay] {} failed after {} attempt(s)" +
                                    " | lastStatus={}",
                            operationName, attempt, e.httpStatus());
                    throw e;
                }

                // ✅ Increment Micrometer retry counter if metrics available
                if (retryIncrement != null) {
                    retryIncrement.run();
                }

                long waitMs = RetryProperties.jitter(delayMs);
                log.warn("[NepalPay] {} failed (attempt {}/{}) | httpStatus={}" +
                                " | retrying in {}ms",
                        operationName, attempt,
                        retryProps.maxAttempts(), e.httpStatus(), waitMs);

                sleepForRetry(waitMs, e);
                delayMs = retryProps.nextDelay(delayMs);

            } catch (Exception e) {
                throw new EsewaException(
                        "Unexpected error during " + operationName
                                + ": " + e.getMessage(), e);
            }
        }
    }

    private void sleepForRetry(long waitMs, EsewaException onInterrupt) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw onInterrupt;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Signature + Decode
    // ─────────────────────────────────────────────────────────────────────

    private EsewaCallbackData decodeCallbackData(String encodedData) {
        try {
            byte[] decodedBytes = Base64.getDecoder()
                    .decode(encodedData.trim());
            String jsonString = new String(decodedBytes, StandardCharsets.UTF_8);
            log.debug("[NepalPay] eSewa decoded callback JSON: {}", jsonString);
            return OBJECT_MAPPER.readValue(jsonString, EsewaCallbackData.class);
        } catch (IllegalArgumentException e) {
            throw new EsewaException(
                    "Failed to decode eSewa Base64 callback data. " +
                            "Pass the raw 'data' query parameter from the redirect URL.",
                    e);
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to parse eSewa callback JSON: "
                            + e.getMessage(), e);
        }
    }

    private void verifyCallbackSignature(EsewaCallbackData data) {
        try {
            String signedFields = data.signedFieldNames();
            if (signedFields == null || signedFields.isBlank()) {
                throw new EsewaException(
                        "signed_field_names is missing from eSewa callback");
            }

            StringBuilder messageBuilder = new StringBuilder();
            String[] fields = signedFields.split(",");
            for (int i = 0; i < fields.length; i++) {
                String field = fields[i].trim();
                String value = getCallbackFieldValue(data, field);
                messageBuilder.append(field).append("=").append(value);
                if (i < fields.length - 1) messageBuilder.append(",");
            }

            String expectedSignature = generateHmacSignature(
                    messageBuilder.toString(), props.secretKey());

            if (!expectedSignature.equals(data.signature())) {
                log.error("[NepalPay] eSewa SIGNATURE MISMATCH — " +
                                "possible tampering! uuid={}",
                        data.transactionUuid());

                // ✅ Increment signature failure counter if metrics available
                if (metrics != null) {
                    metrics.incrementSignatureFailed();
                }

                throw new EsewaException(
                        "eSewa callback signature verification FAILED. " +
                                "Possible tampered response. uuid="
                                + data.transactionUuid());
            }

            log.debug("[NepalPay] eSewa callback signature OK | uuid={}",
                    data.transactionUuid());

        } catch (EsewaException e) {
            throw e;
        } catch (Exception e) {
            throw new EsewaException(
                    "Error during eSewa signature verification: "
                            + e.getMessage(), e);
        }
    }

    private String generateHmacSignature(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to generate HMAC-SHA256 signature: "
                            + e.getMessage(), e);
        }
    }

    private String buildSignatureMessage(
            String totalAmount, String transactionUuid, String productCode) {
        return "total_amount=" + totalAmount
                + ",transaction_uuid=" + transactionUuid
                + ",product_code=" + productCode;
    }

    private String getCallbackFieldValue(
            EsewaCallbackData data, String fieldName) {
        return switch (fieldName.trim()) {
            case "transaction_code"   -> nullToEmpty(data.transactionCode());
            case "status"             -> nullToEmpty(data.status());
            case "total_amount"       -> nullToEmpty(data.totalAmount());
            case "transaction_uuid"   -> nullToEmpty(data.transactionUuid());
            case "product_code"       -> nullToEmpty(data.productCode());
            case "signed_field_names" -> nullToEmpty(data.signedFieldNames());
            default -> {
                log.warn("[NepalPay] Unknown signed field in eSewa callback: {}",
                        fieldName);
                yield "";
            }
        };
    }

    private void validateBuildRequest(
            BigDecimal amount, String transactionUuid) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new EsewaException(
                    "Amount must be greater than 0 NPR. Got: " + amount);
        }
        if (transactionUuid == null || transactionUuid.isBlank()) {
            throw new EsewaException(
                    "transactionUuid is required and cannot be blank");
        }
        if (props.secretKey() == null || props.secretKey().isBlank()) {
            throw new EsewaException(
                    "eSewa secret key not configured. " +
                            "Set nepalpay.esewa.secret-key in application.yml");
        }
        if (props.productCode() == null || props.productCode().isBlank()) {
            throw new EsewaException(
                    "eSewa product code not configured. " +
                            "Set nepalpay.esewa.product-code. Sandbox: EPAYTEST");
        }
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String readBodySafely(
            org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}