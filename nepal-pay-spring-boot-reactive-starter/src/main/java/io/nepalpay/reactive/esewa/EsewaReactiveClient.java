package io.nepalpay.reactive.esewa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nepalpay.core.esewa.model.EsewaCallbackData;
import io.nepalpay.core.esewa.model.EsewaFormPayload;
import io.nepalpay.core.esewa.model.EsewaStatusResponse;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.reactive.config.NepalPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Reactive eSewa Payment Gateway Client.
 *
 * <p>Uses {@link WebClient} and returns {@link Mono} responses.
 *
 * <p>Note: {@link #buildFormPayload} and {@link #verifyCallbackSignature}
 * are synchronous (no HTTP call needed — pure HMAC computation).
 * Only {@link #checkStatus} is reactive (makes an HTTP call).
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class EsewaReactiveClient {

    // ── URLs ──────────────────────────────────────────────────────────────
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

    private static final String HMAC_ALGORITHM     = "HmacSHA256";
    private static final String SIGNED_FIELD_NAMES =
            "total_amount,transaction_uuid,product_code";

    // Shared singleton — thread-safe after construction
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.EsewaProperties props;
    private final WebClient webClient;
    private final String formActionUrl;
    private final RetryProperties retryProps;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     */
    public EsewaReactiveClient(
            NepalPayProperties.EsewaProperties props,
            WebClient.Builder webClientBuilder) {

        this(props, webClientBuilder,
                props.sandbox() ? SANDBOX_STATUS_BASE_URL : PROD_STATUS_BASE_URL);
    }

    /**
     * Test constructor — custom status API base URL.
     */
    public EsewaReactiveClient(
            NepalPayProperties.EsewaProperties props,
            WebClient.Builder webClientBuilder,
            String statusBaseUrlOverride) {

        this.props         = props;
        this.formActionUrl = props.sandbox()
                ? SANDBOX_FORM_URL : PRODUCTION_FORM_URL;
        this.retryProps    = props.retryOrDefault();

        this.webClient = webClientBuilder
                .baseUrl(statusBaseUrlOverride)
                .build();

        log.info("[NepalPay Reactive] EsewaReactiveClient initialized" +
                        " | mode={} | statusUrl={} | retry={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                statusBaseUrlOverride,
                this.retryProps.summary());
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build signed eSewa form payload — synchronous (no HTTP call).
     *
     * @param amount          Transaction amount in NPR
     * @param transactionUuid Your unique transaction UUID (store in DB!)
     * @return Signed form payload
     */
    public EsewaFormPayload buildFormPayload(
            BigDecimal amount, String transactionUuid) {

        return buildFormPayload(
                amount, BigDecimal.ZERO, transactionUuid,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Build signed eSewa form payload with all charges — synchronous.
     */
    public EsewaFormPayload buildFormPayload(
            BigDecimal amount, BigDecimal taxAmount,
            String transactionUuid,
            BigDecimal serviceCharge, BigDecimal deliveryCharge) {

        validateBuildRequest(amount, transactionUuid);

        BigDecimal tax      = taxAmount      != null ? taxAmount      : BigDecimal.ZERO;
        BigDecimal service  = serviceCharge  != null ? serviceCharge  : BigDecimal.ZERO;
        BigDecimal delivery = deliveryCharge != null ? deliveryCharge : BigDecimal.ZERO;

        BigDecimal total = amount.add(tax).add(service).add(delivery)
                .setScale(2, RoundingMode.HALF_UP);

        String amountStr   = format(amount);
        String taxStr      = format(tax);
        String totalStr    = format(total);
        String serviceStr  = format(service);
        String deliveryStr = format(delivery);

        String message   = "total_amount=" + totalStr
                + ",transaction_uuid=" + transactionUuid
                + ",product_code=" + props.productCode();
        String signature = generateHmac(message, props.secretKey());

        return new EsewaFormPayload(
                amountStr, taxStr, totalStr, transactionUuid,
                props.productCode(), serviceStr, deliveryStr,
                props.successUrl(), props.failureUrl(),
                SIGNED_FIELD_NAMES, signature, formActionUrl);
    }

    /**
     * Verify eSewa callback — reactive.
     *
     * <p>Steps:
     * <ol>
     *   <li>Decode Base64 → {@link EsewaCallbackData} (sync)</li>
     *   <li>Verify HMAC signature (sync)</li>
     *   <li>Call status API — {@link Mono} (async)</li>
     * </ol>
     *
     * @param encodedData Base64 "data" param from eSewa redirect
     * @return Mono emitting verification result
     */
    public Mono<EsewaVerificationResult> verifyCallback(String encodedData) {
        if (encodedData == null || encodedData.isBlank()) {
            return Mono.error(
                    new EsewaException("eSewa callback data cannot be null or blank"));
        }

        // Steps 1 + 2 are synchronous — wrap in Mono.fromCallable
        return Mono.fromCallable(() -> {
                    EsewaCallbackData callbackData = decodeCallbackData(encodedData);
                    verifyCallbackSignature(callbackData);
                    return callbackData;
                })
                .flatMap(callbackData ->
                        checkStatus(callbackData.transactionUuid(),
                                callbackData.totalAmount())
                                .map(statusResponse -> new EsewaVerificationResult(
                                        callbackData,
                                        statusResponse,
                                        statusResponse.isPaymentSuccessful())))
                .doOnNext(result -> log.info(
                        "[NepalPay Reactive] eSewa callback verified" +
                                " | uuid={} | success={}",
                        result.callbackData().transactionUuid(),
                        result.isPaymentSuccessful()));
    }

    /**
     * Check eSewa transaction status — reactive.
     *
     * @param transactionUuid Your original transaction UUID
     * @param totalAmount     The exact total amount string
     * @return Mono emitting status response
     */
    public Mono<EsewaStatusResponse> checkStatus(
            String transactionUuid, String totalAmount) {

        if (transactionUuid == null || transactionUuid.isBlank()) {
            return Mono.error(
                    new EsewaException("transactionUuid cannot be null or blank"));
        }
        if (totalAmount == null || totalAmount.isBlank()) {
            return Mono.error(
                    new EsewaException("totalAmount cannot be null or blank"));
        }

        log.debug("[NepalPay Reactive] eSewa status check | uuid={}",
                transactionUuid);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(STATUS_PATH)
                        .queryParam("product_code",     props.productCode())
                        .queryParam("total_amount",     totalAmount)
                        .queryParam("transaction_uuid", transactionUuid)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] eSewa status" +
                                                    " 4xx | uuid={} | body={}",
                                            transactionUuid, body);
                                    return Mono.error(new EsewaException(
                                            "eSewa status check failed",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] eSewa status 5xx | uuid={}",
                            transactionUuid);
                    return Mono.error(new EsewaException(
                            "eSewa server error during status check",
                            res.statusCode().value(), null));
                })
                .bodyToMono(EsewaStatusResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] eSewa status result" +
                                " | uuid={} | status={}",
                        transactionUuid, res.status()))
                .transform(this::withRetry);
    }

    /** Generate a unique transaction UUID. */
    public static String generateTransactionUuid() {
        return UUID.randomUUID().toString();
    }

    /** @return form action URL */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if sandbox mode */
    public boolean isSandbox() { return props.sandbox(); }

    // ─────────────────────────────────────────────────────────────────────
    // VERIFICATION RESULT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Result of {@link #verifyCallback}.
     */
    public record EsewaVerificationResult(
            EsewaCallbackData callbackData,
            EsewaStatusResponse statusResponse,
            boolean verified
    ) {
        public boolean isPaymentSuccessful() { return verified; }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private <T> Mono<T> withRetry(Mono<T> source) {
        if (!retryProps.isActive()) {
            return source;
        }
        return source.retryWhen(
                Retry.backoff(
                                retryProps.maxAttempts(),
                                Duration.ofMillis(retryProps.initialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryProps.maxDelayMs()))
                        .jitter(0.1)
                        .filter(t -> t instanceof EsewaException ee
                                && (ee.httpStatus() == 0 || ee.httpStatus() >= 500))
                        .doBeforeRetry(s -> log.warn(
                                "[NepalPay Reactive] eSewa retry attempt {}",
                                s.totalRetries() + 1))
        ).onErrorMap(ex ->
                ex instanceof reactor.util.retry.Retry.RetryExhaustedException
                        ? ex.getCause() : ex);
    }

    private EsewaCallbackData decodeCallbackData(String encodedData) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedData.trim());
            String json    = new String(decoded, StandardCharsets.UTF_8);
            return OBJECT_MAPPER.readValue(json, EsewaCallbackData.class);
        } catch (IllegalArgumentException e) {
            throw new EsewaException(
                    "Failed to decode eSewa Base64 callback data.", e);
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to parse eSewa callback JSON: " + e.getMessage(), e);
        }
    }

    private void verifyCallbackSignature(EsewaCallbackData data) {
        String signedFields = data.signedFieldNames();
        if (signedFields == null || signedFields.isBlank()) {
            throw new EsewaException(
                    "signed_field_names is missing from eSewa callback");
        }

        StringBuilder msg = new StringBuilder();
        String[] fields = signedFields.split(",");
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i].trim();
            msg.append(field).append("=")
                    .append(getFieldValue(data, field));
            if (i < fields.length - 1) msg.append(",");
        }

        String expected = generateHmac(msg.toString(), props.secretKey());
        if (!expected.equals(data.signature())) {
            log.error("[NepalPay Reactive] eSewa SIGNATURE MISMATCH | uuid={}",
                    data.transactionUuid());
            throw new EsewaException(
                    "eSewa callback signature verification FAILED. " +
                            "uuid=" + data.transactionUuid());
        }
    }

    private String generateHmac(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to generate HMAC-SHA256: " + e.getMessage(), e);
        }
    }

    private String getFieldValue(EsewaCallbackData data, String field) {
        return switch (field.trim()) {
            case "transaction_code"   -> safe(data.transactionCode());
            case "status"             -> safe(data.status());
            case "total_amount"       -> safe(data.totalAmount());
            case "transaction_uuid"   -> safe(data.transactionUuid());
            case "product_code"       -> safe(data.productCode());
            case "signed_field_names" -> safe(data.signedFieldNames());
            default                   -> "";
        };
    }

    private void validateBuildRequest(BigDecimal amount, String uuid) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new EsewaException(
                    "Amount must be greater than 0. Got: " + amount);
        }
        if (uuid == null || uuid.isBlank()) {
            throw new EsewaException(
                    "transactionUuid is required and cannot be blank");
        }
    }

    private String format(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String safe(String v) { return v != null ? v : ""; }
}