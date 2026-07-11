package io.nepalpay.reactive.esewa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nepalpay.core.esewa.model.EsewaCallbackData;
import io.nepalpay.core.esewa.model.EsewaFormPayload;
import io.nepalpay.core.esewa.model.EsewaStatusResponse;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.metrics.EsewaMetrics;
import io.nepalpay.reactive.config.NepalPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Reactive eSewa Payment Gateway Client.
 *
 * <p>Uses Spring {@link WebClient} and returns {@link Mono} responses.
 *
 * <p><strong>Metrics:</strong>
 * If constructed with a {@link MeterRegistry}, HTTP operations are timed
 * via {@link Timer.Sample} + reactive {@code doOnSuccess}/{@code doOnError}.
 * Signature failures increment a dedicated counter.
 * Timer accessors are exposed by {@link EsewaMetrics} — not by this class.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class EsewaReactiveClient {

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

    // ── Constants ─────────────────────────────────────────────────────────
    private static final String HMAC_ALGORITHM     = "HmacSHA256";
    private static final String SIGNED_FIELD_NAMES =
            "total_amount,transaction_uuid,product_code";

    /**
     * Shared singleton ObjectMapper — thread-safe after construction.
     * Avoids creating a new instance on every callback.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.EsewaProperties props;
    private final WebClient       webClient;
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
     * Production constructor — no metrics.
     * Used by auto-configuration when Actuator is absent.
     *
     * @param props            eSewa properties from application.yml
     * @param webClientBuilder WebClient builder
     */
    public EsewaReactiveClient(
            NepalPayProperties.EsewaProperties props,
            WebClient.Builder webClientBuilder) {

        this(props, webClientBuilder,
                props.sandbox()
                        ? SANDBOX_STATUS_BASE_URL : PROD_STATUS_BASE_URL,
                null);
    }

    /**
     * Test constructor — custom status API base URL, no metrics.
     *
     * <p>Used in tests to point the client at {@code MockWebServer}.
     *
     * <p><strong>NOTE:</strong>
     * A separate {@code EsewaReactiveClient(props, builder, MeterRegistry)}
     * overload was NOT added — it would be ambiguous with this constructor
     * when {@code null} is passed. Use the 4-arg constructor to inject metrics.
     *
     * @param props                 eSewa properties
     * @param webClientBuilder      WebClient builder
     * @param statusBaseUrlOverride Custom base URL (MockWebServer URL)
     */
    public EsewaReactiveClient(
            NepalPayProperties.EsewaProperties props,
            WebClient.Builder webClientBuilder,
            String statusBaseUrlOverride) {

        this(props, webClientBuilder, statusBaseUrlOverride, null);
    }

    /**
     * Full constructor — custom status URL + optional metrics.
     *
     * <p>Used by {@code NepalPayReactiveMetricsAutoConfiguration} to inject
     * a {@link MeterRegistry} when Actuator is on the classpath.
     * Exposed as {@code public} to avoid constructor ambiguity.
     *
     * @param props                 eSewa properties from application.yml
     * @param webClientBuilder      WebClient builder
     * @param statusBaseUrlOverride Status API base URL
     * @param meterRegistry         Micrometer registry — null = no metrics
     */
    public EsewaReactiveClient(
            NepalPayProperties.EsewaProperties props,
            WebClient.Builder webClientBuilder,
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

        this.webClient = webClientBuilder
                .baseUrl(statusBaseUrlOverride)
                .build();

        log.info("[NepalPay Reactive] EsewaReactiveClient initialized" +
                        " | mode={} | statusUrl={} | retry={} | metrics={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                statusBaseUrlOverride,
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build signed eSewa form payload — synchronous (no HTTP).
     * Tax, service charge, and delivery charge default to zero.
     *
     * @param amount          Transaction amount in NPR
     * @param transactionUuid Your unique transaction ID (store in DB!)
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
     *
     * @param amount          Base amount in NPR
     * @param taxAmount       Tax in NPR (null = zero)
     * @param transactionUuid Your unique transaction ID (store in DB!)
     * @param serviceCharge   Service charge in NPR (null = zero)
     * @param deliveryCharge  Delivery charge in NPR (null = zero)
     * @return Signed form payload
     * @throws EsewaException if validation or signature generation fails
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

        if (tax.signum() < 0 || service.signum() < 0 || delivery.signum() < 0) {
            throw new EsewaException(
                    "Charge components cannot be negative");
        }

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
     * <p>Performs three steps automatically:
     * <ol>
     *   <li>Decodes Base64 callback data</li>
     *   <li>Verifies HMAC-SHA256 signature (constant-time comparison)</li>
     *   <li>Calls eSewa status API</li>
     * </ol>
     *
     * <p>Records {@code nepalpay.esewa.callback.verify.duration} timer.
     * Records {@code nepalpay.esewa.callback.signature.failed} counter
     * when HMAC verification fails.
     *
     * @param encodedData Base64 "data" param from eSewa redirect
     * @return Mono of verification result
     */
    public Mono<EsewaVerificationResult> verifyCallback(String encodedData) {
        if (encodedData == null || encodedData.isBlank()) {
            return Mono.error(new EsewaException(
                    "eSewa callback data cannot be null or blank"));
        }

        Mono<EsewaVerificationResult> mono = Mono.fromCallable(() -> {
                    EsewaCallbackData callbackData = decodeCallbackData(encodedData);
                    verifyCallbackSignature(callbackData);
                    return callbackData;
                })
                .flatMap(callbackData ->
                        checkStatus(
                                callbackData.transactionUuid(),
                                callbackData.totalAmount())
                                .map(statusResponse ->
                                        new EsewaVerificationResult(
                                                callbackData,
                                                statusResponse,
                                                statusResponse.isPaymentSuccessful())))
                .doOnNext(result -> log.info(
                        "[NepalPay Reactive] eSewa callback verified" +
                                " | uuid={} | success={}",
                        result.callbackData().transactionUuid(),
                        result.isPaymentSuccessful()));

        return timedVerify(mono);
    }

    /**
     * Check eSewa transaction status — reactive.
     *
     * <p>Records {@code nepalpay.esewa.status.check.duration} timer.
     *
     * @param transactionUuid Your original transaction UUID
     * @param totalAmount     Exact total amount from original transaction
     * @return Mono of status response
     */
    public Mono<EsewaStatusResponse> checkStatus(
            String transactionUuid, String totalAmount) {

        if (transactionUuid == null || transactionUuid.isBlank()) {
            return Mono.error(new EsewaException(
                    "transactionUuid cannot be null or blank"));
        }
        if (totalAmount == null || totalAmount.isBlank()) {
            return Mono.error(new EsewaException(
                    "totalAmount cannot be null or blank"));
        }

        log.debug("[NepalPay Reactive] eSewa status check | uuid={}",
                transactionUuid);

        Mono<EsewaStatusResponse> mono = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(STATUS_PATH)
                        .queryParam("product_code", props.productCode())
                        .queryParam("total_amount", totalAmount)
                        .queryParam("transaction_uuid", transactionUuid)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] eSewa" +
                                                    " status 4xx | uuid={} | body={}",
                                            transactionUuid, body);
                                    return Mono.error(new EsewaException(
                                            "eSewa status check failed",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] eSewa status 5xx" +
                            " | uuid={}", transactionUuid);
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

        return timedStatus(mono);
    }

    /** @return UUID suitable for eSewa transactions */
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
     * @param callbackData   Decoded callback data from eSewa redirect
     * @param statusResponse Response from eSewa status API
     * @param verified       True only if HMAC matched AND status is COMPLETE
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
    // PRIVATE — Reactive timer helpers
    //
    // Uses Timer.Sample + doOnSuccess/doOnError — correct reactive pattern.
    // Timer accessors come from EsewaMetrics (not this class).
    // Supplier<T> is BLOCKING — never use in reactive pipeline.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Wraps a Mono with verifyCallback timing.
     * No-op when metrics is null.
     */
    private <T> Mono<T> timedVerify(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(
                            metrics.verifySuccessTimer()))
                    .doOnError(e -> sample.stop(
                            metrics.verifyErrorTimer()));
        });
    }

    /**
     * Wraps a Mono with checkStatus timing.
     * No-op when metrics is null.
     */
    private <T> Mono<T> timedStatus(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(
                            metrics.statusSuccessTimer()))
                    .doOnError(e -> sample.stop(
                            metrics.statusErrorTimer()));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Retry
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Applies Reactor retry with exponential backoff.
     * Increments EsewaMetrics status retry counter on each attempt.
     */
    private <T> Mono<T> withRetry(Mono<T> source) {
        if (!retryProps.isActive()) return source;
        return source.retryWhen(
                Retry.backoff(
                                retryProps.maxAttempts(),
                                Duration.ofMillis(retryProps.initialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryProps.maxDelayMs()))
                        .jitter(0.1)
                        .filter(t ->
                                t instanceof WebClientRequestException
                                        || (t instanceof EsewaException ee
                                        && (ee.httpStatus() == 0
                                        || ee.httpStatus() >= 500)))
                        .doBeforeRetry(s -> {
                            log.warn("[NepalPay Reactive] eSewa retry" +
                                            " attempt {}",
                                    s.totalRetries() + 1);
                            if (metrics != null) {
                                metrics.incrementStatusRetry();
                            }
                        })
        ).onErrorMap(
                Exceptions::isRetryExhausted,
                Throwable::getCause
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Signature + Decode
    // ─────────────────────────────────────────────────────────────────────

    private EsewaCallbackData decodeCallbackData(String encodedData) {
        try {
            byte[] decoded = Base64.getDecoder()
                    .decode(encodedData.trim());
            String json = new String(decoded, StandardCharsets.UTF_8);
            return OBJECT_MAPPER.readValue(json, EsewaCallbackData.class);
        } catch (IllegalArgumentException e) {
            throw new EsewaException(
                    "Failed to decode eSewa Base64 callback data.", e);
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to parse eSewa callback JSON: "
                            + e.getMessage(), e);
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

        byte[] expectedBytes = generateHmacBytes(
                msg.toString(), props.secretKey());

        // Decode received signature — invalid Base64 = definitive mismatch
        byte[] receivedBytes;
        try {
            receivedBytes = Base64.getDecoder().decode(
                    data.signature() != null ? data.signature() : "");
        } catch (IllegalArgumentException e) {
            log.error("[NepalPay Reactive] eSewa SIGNATURE MISMATCH" +
                            " (invalid Base64) | uuid={}",
                    data.transactionUuid());
            if (metrics != null) {
                metrics.incrementSignatureFailed();
            }
            throw new EsewaException(
                    "eSewa callback signature verification FAILED. " +
                            "uuid=" + data.transactionUuid());
        }

        // Constant-time comparison — prevents timing attacks
        if (!MessageDigest.isEqual(expectedBytes, receivedBytes)) {
            log.error("[NepalPay Reactive] eSewa SIGNATURE MISMATCH" +
                    " | uuid={}", data.transactionUuid());
            if (metrics != null) {
                metrics.incrementSignatureFailed();
            }
            throw new EsewaException(
                    "eSewa callback signature verification FAILED. " +
                            "uuid=" + data.transactionUuid());
        }

        log.debug("[NepalPay Reactive] eSewa callback signature OK" +
                " | uuid={}", data.transactionUuid());
    }

    private byte[] generateHmacBytes(String message, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new EsewaException(
                    "Failed to generate HMAC-SHA256: " + e.getMessage(), e);
        }
    }

    private String generateHmac(String message, String secretKey) {
        return Base64.getEncoder()
                .encodeToString(generateHmacBytes(message, secretKey));
    }

    private String getFieldValue(EsewaCallbackData data, String field) {
        return switch (field.trim()) {
            case "transaction_code"   -> safe(data.transactionCode());
            case "status"             -> safe(data.status());
            case "total_amount"       -> safe(data.totalAmount());
            case "transaction_uuid"   -> safe(data.transactionUuid());
            case "product_code"       -> safe(data.productCode());
            case "signed_field_names" -> safe(data.signedFieldNames());
            default -> {
                log.warn("[NepalPay Reactive] Unknown signed field: {}",
                        field);
                yield "";
            }
        };
    }

    private void validateBuildRequest(BigDecimal amount, String uuid) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new EsewaException(
                    "Amount must be greater than 0 NPR. Got: " + amount);
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