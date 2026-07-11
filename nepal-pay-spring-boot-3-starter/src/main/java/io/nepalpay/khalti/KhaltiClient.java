package io.nepalpay.khalti;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.metrics.KhaltiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Khalti Payment Gateway Client — Spring Boot 3.
 *
 * <p>Provides three operations:
 * <ul>
 *   <li>{@link #initiatePayment}  — Start payment, get redirect URL</li>
 *   <li>{@link #lookupPayment}    — Verify payment after callback</li>
 *   <li>{@link #refundPayment}    — Refund a completed payment</li>
 * </ul>
 *
 * <p>Official Khalti docs: https://docs.khalti.com/khalti-epayment/
 *
 * <p><strong>URL structure note:</strong>
 * Khalti uses two different API path prefixes:
 * <ul>
 *   <li>Initiate + Lookup: {@code /api/v2/epayment/...}</li>
 *   <li>Refund:            {@code /api/merchant-transaction/{id}/refund/}</li>
 * </ul>
 * The refund path has no {@code /api/v2} segment.
 * We handle this via a separate {@code baseDomain} field.
 *
 * <p><strong>Metrics:</strong>
 * When {@code spring-boot-starter-actuator} is on the classpath and a
 * {@link MeterRegistry} is injected, all three operations are timed and
 * retry attempts are counted automatically. If no {@link MeterRegistry}
 * is available, all metric recording is silently skipped — zero impact
 * on existing users without Actuator.
 *
 * <p><strong>Retry:</strong>
 * All HTTP methods are wrapped with exponential backoff retry.
 * Retry is disabled by default — enable via
 * {@code nepalpay.khalti.retry.enabled=true}.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiClient {

    // ── Official Khalti API base URLs ─────────────────────────────────────
    private static final String SANDBOX_BASE_URL       = "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL    = "https://khalti.com/api/v2";

    // ── Base domains for building refund URL ──────────────────────────────
    private static final String SANDBOX_BASE_DOMAIN    = "https://dev.khalti.com";
    private static final String PRODUCTION_BASE_DOMAIN = "https://khalti.com";

    // ── Endpoint paths ────────────────────────────────────────────────────
    private static final String INITIATE_PATH      = "/epayment/initiate/";
    private static final String LOOKUP_PATH        = "/epayment/lookup/";
    private static final String REFUND_PATH_PREFIX = "/api/merchant-transaction/";
    private static final String REFUND_PATH_SUFFIX = "/refund/";

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.KhaltiProperties props;
    private final RestClient      restClient;
    private final String          baseUrl;
    private final String          baseDomain;
    private final RetryProperties retryProps;

    /**
     * Optional Micrometer metrics — null when Actuator not on classpath.
     * All usages guarded with null check — no NPE possible.
     */
    private final KhaltiMetrics metrics;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     * No metrics (Actuator not available or not configured).
     *
     * @param props             Khalti properties from application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     */
    public KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder) {

        this(props, restClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN,
                null);
    }

    /**
     * Constructor that accepts an optional {@link MeterRegistry}.
     *
     * <p>When {@code meterRegistry} is non-null, all HTTP operations
     * are timed and retry attempts are counted via {@link KhaltiMetrics}.
     * When null, behaviour is identical to the no-metrics constructor.
     *
     * <p>This constructor will be used by
     * {@code NepalPayMetricsAutoConfiguration} (coming in Step 12)
     * to wire metrics automatically when Actuator is on the classpath.
     *
     * @param props             Khalti properties from application.yml
     * @param restClientBuilder Spring Boot RestClient builder
     * @param meterRegistry     Micrometer registry — null means no metrics
     */
    public KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry) {

        this(props, restClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN,
                meterRegistry);
    }

    /**
     * Test constructor — allows injecting a custom base URL.
     * No metrics.
     *
     * @param props             Khalti properties
     * @param restClientBuilder RestClient builder
     * @param baseUrlOverride   Custom base URL (MockWebServer URL)
     */
    public KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder,
            String baseUrlOverride) {

        this(props, restClientBuilder,
                baseUrlOverride,
                baseUrlOverride.endsWith("/")
                        ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1)
                        : baseUrlOverride,
                null);
    }

    /**
     * Core private constructor — single point of initialization.
     *
     * @param props             Khalti properties
     * @param restClientBuilder RestClient builder
     * @param baseUrl           API v2 base URL
     * @param baseDomain        Base domain for refund URL construction
     * @param meterRegistry     Micrometer registry — null means no metrics
     */
    private KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String baseDomain,
            MeterRegistry meterRegistry) {

        this.props      = props;
        this.baseUrl    = baseUrl;
        this.baseDomain = baseDomain;
        this.retryProps = props.retryOrDefault();

        // ── Metrics — optional ────────────────────────────────────────────
        // KhaltiMetrics is null when no MeterRegistry is available.
        // All metric recording below is guarded with null checks —
        // zero NPE risk, zero impact on users without Actuator.
        this.metrics = (meterRegistry != null)
                ? new KhaltiMetrics(meterRegistry, props.sandbox())
                : null;

        // ── HTTP client ───────────────────────────────────────────────────
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));

        this.restClient = restClientBuilder
                .baseUrl(this.baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Key " + props.secretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] KhaltiClient initialized | mode={} | baseUrl={}" +
                        " | baseDomain={} | timeout={}s | retry={} | metrics={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                this.baseUrl,
                this.baseDomain,
                props.timeoutSeconds(),
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment.
     *
     * <p>Store {@code pidx} in your database BEFORE redirecting the user.
     *
     * <p>Records {@code nepalpay.khalti.payment.initiate.duration} timer
     * when Micrometer is available.
     *
     * @param request Payment details — use {@link KhaltiInitiateRequest#builder()}
     * @return Response with {@code pidx} and {@code paymentUrl}
     * @throws KhaltiException if validation fails or Khalti API returns error
     */
    public KhaltiInitiateResponse initiatePayment(KhaltiInitiateRequest request) {
        validateInitiateRequest(request);
        KhaltiInitiateRequest finalRequest = withDefaults(request);

        log.debug("[NepalPay] Khalti initiate | orderId={} | amount={} paisa",
                finalRequest.purchaseOrderId(), finalRequest.amount());

        // ✅ Wrap with metrics timer if available — falls back to direct
        // call if metrics is null (Actuator not on classpath)
        if (metrics != null) {
            return metrics.recordInitiate(
                    () -> executeInitiate(finalRequest));
        }
        return executeInitiate(finalRequest);
    }

    /**
     * Lookup (verify) a Khalti payment by its {@code pidx}.
     *
     * <p>ALWAYS call this after receiving the callback redirect.
     *
     * <p>Records {@code nepalpay.khalti.payment.lookup.duration} timer
     * when Micrometer is available.
     *
     * @param pidx The payment identifier from {@link #initiatePayment}
     * @return Verified payment details including status and amount
     * @throws KhaltiException if pidx is invalid or API call fails
     */
    public KhaltiLookupResponse lookupPayment(String pidx) {
        if (pidx == null || pidx.isBlank()) {
            throw new KhaltiException("pidx cannot be null or blank");
        }

        log.debug("[NepalPay] Khalti lookup | pidx={}", pidx);

        if (metrics != null) {
            return metrics.recordLookup(() -> executeLookup(pidx));
        }
        return executeLookup(pidx);
    }

    /**
     * Refund a completed Khalti payment — FULL refund.
     *
     * <p>Use {@code transactionId} from {@link #lookupPayment}, NOT pidx.
     *
     * <p>Records {@code nepalpay.khalti.payment.refund.duration} timer
     * when Micrometer is available.
     *
     * @param transactionId Khalti's internal transaction ID
     * @return Refund confirmation response
     * @throws KhaltiException if transactionId is blank or API fails
     */
    public KhaltiRefundResponse refundPayment(String transactionId) {
        return executeRefundRequest(transactionId, null);
    }

    /**
     * Refund a completed Khalti payment — PARTIAL refund.
     *
     * @param transactionId Khalti's internal transaction ID
     * @param amountPaisa   Amount to refund in PAISA (NPR × 100)
     * @return Refund confirmation response
     * @throws KhaltiException if inputs are invalid or API fails
     */
    public KhaltiRefundResponse refundPayment(String transactionId, Long amountPaisa) {
        if (amountPaisa != null && amountPaisa <= 0) {
            throw new KhaltiException(
                    "amountPaisa must be greater than 0 for partial refund. Got: "
                            + amountPaisa
                            + ". Use refundPayment(transactionId) for full refund.");
        }
        return executeRefundRequest(transactionId, amountPaisa);
    }

    /**
     * Returns true if operating in sandbox (test) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() { return props.sandbox(); }

    /**
     * Returns the current active API v2 base URL.
     *
     * @return sandbox or production API v2 base URL
     */
    public String baseUrl() { return baseUrl; }

    /**
     * Returns the current active base domain.
     * Used to build the refund endpoint URL.
     *
     * @return sandbox or production base domain (no trailing slash)
     */
    public String baseDomain() { return baseDomain; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — HTTP execution methods
    // (separated from public API so metrics wrap cleanly)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Executes the actual initiatePayment HTTP call with retry.
     * Called by public {@link #initiatePayment} — either directly
     * or wrapped inside a metrics timer.
     */
    private KhaltiInitiateResponse executeInitiate(
            KhaltiInitiateRequest finalRequest) {

        return executeWithRetry("Khalti initiate",
                metrics != null ? metrics::incrementInitiateRetry : null,
                () -> {
                    KhaltiInitiateResponse response = restClient.post()
                            .uri(INITIATE_PATH)
                            .body(finalRequest)
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError,
                                    (req, res) -> {
                                        String body = readBodySafely(res);
                                        log.error("[NepalPay] Khalti initiate 4xx" +
                                                        " | status={} | body={}",
                                                res.getStatusCode().value(), body);
                                        throw new KhaltiException(
                                                "Khalti payment initiation failed — " +
                                                        "check your secret key or request",
                                                res.getStatusCode().value(), body);
                                    })
                            .onStatus(HttpStatusCode::is5xxServerError,
                                    (req, res) -> {
                                        log.error("[NepalPay] Khalti initiate 5xx" +
                                                        " | status={}",
                                                res.getStatusCode().value());
                                        throw new KhaltiException(
                                                "Khalti server error — please try again",
                                                res.getStatusCode().value(), null);
                                    })
                            .body(KhaltiInitiateResponse.class);

                    if (response == null) {
                        throw new KhaltiException(
                                "Khalti returned empty response for initiate");
                    }

                    log.info("[NepalPay] Khalti payment initiated" +
                                    " | pidx={} | orderId={}",
                            response.pidx(), finalRequest.purchaseOrderId());
                    return response;
                });
    }

    /**
     * Executes the actual lookupPayment HTTP call with retry.
     */
    private KhaltiLookupResponse executeLookup(String pidx) {
        return executeWithRetry("Khalti lookup",
                metrics != null ? metrics::incrementLookupRetry : null,
                () -> {
                    KhaltiLookupResponse response = restClient.post()
                            .uri(LOOKUP_PATH)
                            .body(Map.of("pidx", pidx))
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError,
                                    (req, res) -> {
                                        String body = readBodySafely(res);
                                        log.error("[NepalPay] Khalti lookup 4xx" +
                                                        " | pidx={} | status={} | body={}",
                                                pidx,
                                                res.getStatusCode().value(), body);
                                        throw new KhaltiException(
                                                "Khalti lookup failed — " +
                                                        "invalid pidx or unauthorized",
                                                res.getStatusCode().value(), body);
                                    })
                            .onStatus(HttpStatusCode::is5xxServerError,
                                    (req, res) -> {
                                        log.error("[NepalPay] Khalti lookup 5xx" +
                                                        " | pidx={} | status={}",
                                                pidx, res.getStatusCode().value());
                                        throw new KhaltiException(
                                                "Khalti server error during lookup",
                                                res.getStatusCode().value(), null);
                                    })
                            .body(KhaltiLookupResponse.class);

                    if (response == null) {
                        throw new KhaltiException(
                                "Khalti returned empty response for lookup pidx="
                                        + pidx);
                    }

                    log.info("[NepalPay] Khalti lookup result | pidx={}" +
                                    " | status={} | success={}",
                            response.pidx(), response.status(),
                            response.isPaymentSuccessful());
                    return response;
                });
    }

    /**
     * Executes the actual refund HTTP call with retry.
     */
    private KhaltiRefundResponse executeRefundRequest(
            String transactionId, Long amountPaisa) {

        if (transactionId == null || transactionId.isBlank()) {
            throw new KhaltiException(
                    "transactionId cannot be null or blank. " +
                            "Obtain transactionId from lookupPayment() " +
                            "after payment reaches Completed. " +
                            "transactionId is null for Pending/Expired/Canceled.");
        }

        String refundUrl = baseDomain + REFUND_PATH_PREFIX
                + transactionId + REFUND_PATH_SUFFIX;
        Object requestBody = (amountPaisa != null)
                ? Map.of("amount", amountPaisa) : Map.of();

        log.debug("[NepalPay] Khalti refund | txnId={} | type={} | url={}",
                transactionId,
                amountPaisa != null
                        ? "PARTIAL (" + amountPaisa + " paisa)" : "FULL",
                refundUrl);

        Runnable retryIncrement = (metrics != null)
                ? metrics::incrementRefundRetry : null;

        if (metrics != null) {
            return metrics.recordRefund(() ->
                    executeWithRetry("Khalti refund", retryIncrement,
                            () -> doRefund(transactionId, refundUrl, requestBody)));
        }
        return executeWithRetry("Khalti refund", null,
                () -> doRefund(transactionId, refundUrl, requestBody));
    }

    /**
     * The actual HTTP call for refund — separated for metrics wrapping.
     */
    private KhaltiRefundResponse doRefund(
            String transactionId, String refundUrl, Object requestBody) {

        KhaltiRefundResponse response = restClient.post()
                .uri(refundUrl)
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = readBodySafely(res);
                    log.error("[NepalPay] Khalti refund 4xx | txnId={}" +
                                    " | status={} | body={}",
                            transactionId, res.getStatusCode().value(), body);
                    throw new KhaltiException(
                            "Khalti refund failed — payment may not be in a " +
                                    "refundable state. " +
                                    "Only Completed payments can be refunded.",
                            res.getStatusCode().value(), body);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    log.error("[NepalPay] Khalti refund 5xx | txnId={}" +
                                    " | status={}",
                            transactionId, res.getStatusCode().value());
                    throw new KhaltiException(
                            "Khalti server error during refund — try again",
                            res.getStatusCode().value(), null);
                })
                .body(KhaltiRefundResponse.class);

        if (response == null) {
            throw new KhaltiException(
                    "Khalti returned empty response for refund txnId="
                            + transactionId);
        }

        log.info("[NepalPay] Khalti refund result | txnId={}" +
                        " | status={} | refunded={}",
                transactionId, response.status(),
                response.isRefundSuccessful());
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Retry
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Executes an API call with exponential backoff retry.
     *
     * <p>Updated to accept an optional {@code retryIncrement} runnable
     * so the retry counter is incremented on each retry attempt when
     * metrics are available.
     *
     * @param operationName  display name for log messages
     * @param retryIncrement optional — increments retry counter per attempt
     * @param operation      the HTTP call to execute and retry
     * @param <T>            return type
     * @return result from the operation
     */
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

            } catch (KhaltiException e) {
                if (e.httpStatus() >= 400 && e.httpStatus() < 500) {
                    throw e;
                }

                attempt++;

                if (attempt > retryProps.maxAttempts()) {
                    log.error("[NepalPay] {} failed after {} attempt(s)" +
                                    " — no more retries | lastStatus={}",
                            operationName, attempt, e.httpStatus());
                    throw e;
                }

                // Increment Micrometer retry counter if metrics available
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
                throw new KhaltiException(
                        "Unexpected error during " + operationName
                                + ": " + e.getMessage(), e);
            }
        }
    }

    private void sleepForRetry(long waitMs, KhaltiException onInterrupt) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw onInterrupt;
        }
    }

    private void validateInitiateRequest(KhaltiInitiateRequest req) {
        if (req == null) {
            throw new KhaltiException("KhaltiInitiateRequest cannot be null");
        }
        if (req.amount() <= 0) {
            throw new KhaltiException(
                    "Amount must be greater than 0 paisa. Got: " + req.amount() +
                            ". Reminder: NPR × 100 = paisa " +
                            "(minimum NPR 10 = 1000 paisa)");
        }
        if (req.purchaseOrderId() == null || req.purchaseOrderId().isBlank()) {
            throw new KhaltiException(
                    "purchaseOrderId is required and cannot be blank");
        }
        if (req.purchaseOrderName() == null || req.purchaseOrderName().isBlank()) {
            throw new KhaltiException(
                    "purchaseOrderName is required and cannot be blank");
        }
    }

    private KhaltiInitiateRequest withDefaults(KhaltiInitiateRequest req) {
        String returnUrl  = (req.returnUrl()  != null)
                ? req.returnUrl()  : props.returnUrl();
        String websiteUrl = (req.websiteUrl() != null)
                ? req.websiteUrl() : props.websiteUrl();

        if (returnUrl == null || returnUrl.isBlank()) {
            throw new KhaltiException(
                    "returnUrl is required. Set it in the request or via " +
                            "nepalpay.khalti.return-url in application.yml");
        }

        return KhaltiInitiateRequest.builder()
                .amount(req.amount())
                .purchaseOrderId(req.purchaseOrderId())
                .purchaseOrderName(req.purchaseOrderName())
                .returnUrl(returnUrl)
                .websiteUrl(websiteUrl)
                .build();
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