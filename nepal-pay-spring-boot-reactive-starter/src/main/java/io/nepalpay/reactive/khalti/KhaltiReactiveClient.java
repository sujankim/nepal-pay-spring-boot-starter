package io.nepalpay.reactive.khalti;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.core.metrics.KhaltiMetrics;
import io.nepalpay.reactive.config.NepalPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

/**
 * Reactive Khalti Payment Gateway Client.
 *
 * <p>Uses Spring {@link WebClient} and returns {@link Mono} responses.
 * Drop-in reactive replacement for the blocking {@code KhaltiClient}.
 *
 * <p><strong>Metrics:</strong>
 * If constructed with a {@link MeterRegistry}, all three HTTP operations
 * are timed using {@link Timer.Sample} with reactive
 * {@code doOnSuccess}/{@code doOnError}. Retry attempts are counted
 * per operation — initiate, lookup, and refund each have their own counter.
 * When no {@link MeterRegistry} is provided, metrics are silently skipped.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiReactiveClient {

    // ── Official Khalti API base URLs ─────────────────────────────────────
    private static final String SANDBOX_BASE_URL       =
            "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL    =
            "https://khalti.com/api/v2";
    private static final String SANDBOX_BASE_DOMAIN    =
            "https://dev.khalti.com";
    private static final String PRODUCTION_BASE_DOMAIN =
            "https://khalti.com";

    // ── Endpoint paths ────────────────────────────────────────────────────
    private static final String INITIATE_PATH      = "/epayment/initiate/";
    private static final String LOOKUP_PATH        = "/epayment/lookup/";
    private static final String REFUND_PATH_PREFIX = "/api/merchant-transaction/";
    private static final String REFUND_PATH_SUFFIX = "/refund/";

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.KhaltiProperties props;
    private final WebClient       webClient;
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
     * Production constructor — no metrics.
     * Used by auto-configuration when Actuator is absent.
     *
     * @param props            Khalti properties from application.yml
     * @param webClientBuilder WebClient builder
     */
    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder) {

        this(props, webClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN,
                null);
    }

    /**
     * Test constructor — custom base URL, no metrics.
     *
     * <p>Used in tests to point the client at {@code MockWebServer}.
     *
     * @param props            Khalti properties
     * @param webClientBuilder WebClient builder
     * @param baseUrlOverride  Custom base URL (MockWebServer URL)
     */
    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder,
            String baseUrlOverride) {

        this(props, webClientBuilder,
                baseUrlOverride,
                baseUrlOverride.endsWith("/")
                        ? baseUrlOverride.substring(
                        0, baseUrlOverride.length() - 1)
                        : baseUrlOverride,
                null);
    }

    /**
     * Constructor WITH Micrometer metrics.
     * Used by {@code NepalPayReactiveMetricsAutoConfiguration}.
     *
     * <p>Unambiguous 3-arg overload — no String constructor at this arity.
     *
     * @param props            Khalti properties from application.yml
     * @param webClientBuilder WebClient builder
     * @param meterRegistry    Micrometer registry — null = no metrics
     */
    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {

        this(props, webClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN,
                meterRegistry);
    }

    /**
     * Core private constructor — single point of initialization.
     * All public constructors delegate here.
     */
    private KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder,
            String baseUrl,
            String baseDomain,
            MeterRegistry meterRegistry) {

        this.props      = props;
        this.baseUrl    = baseUrl;
        this.baseDomain = baseDomain;
        this.retryProps = props.retryOrDefault();

        // ── Metrics — optional ────────────────────────────────────────────
        this.metrics = (meterRegistry != null)
                ? new KhaltiMetrics(meterRegistry, props.sandbox())
                : null;

        this.webClient = webClientBuilder
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Key " + props.secretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay Reactive] KhaltiReactiveClient initialized" +
                        " | mode={} | baseUrl={} | retry={} | metrics={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                this.baseUrl,
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment — reactive.
     *
     * <p>Wrapped in {@link Mono#defer} so validation errors are
     * delivered as reactive error signals — not thrown directly.
     *
     * <p>Records {@code nepalpay.khalti.payment.initiate.duration}
     * when Micrometer is available.
     *
     * @param request Payment details
     * @return Mono of response with pidx and paymentUrl
     */
    public Mono<KhaltiInitiateResponse> initiatePayment(
            KhaltiInitiateRequest request) {

        return Mono.defer(() -> {
            try {
                validateInitiateRequest(request);
            } catch (KhaltiException e) {
                return Mono.error(e);
            }

            KhaltiInitiateRequest finalRequest;
            try {
                finalRequest = withDefaults(request);
            } catch (KhaltiException e) {
                return Mono.error(e);
            }

            log.debug("[NepalPay Reactive] Khalti initiate | orderId={}" +
                            " | amount={}",
                    finalRequest.purchaseOrderId(), finalRequest.amount());

            Mono<KhaltiInitiateResponse> mono = webClient.post()
                    .uri(INITIATE_PATH)
                    .bodyValue(finalRequest)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.error("[NepalPay Reactive] Khalti" +
                                                        " initiate 4xx | status={}" +
                                                        " | body={}",
                                                res.statusCode().value(), body);
                                        return Mono.error(new KhaltiException(
                                                "Khalti payment initiation" +
                                                        " failed — check your secret" +
                                                        " key or request",
                                                res.statusCode().value(), body));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, res -> {
                        log.error("[NepalPay Reactive] Khalti initiate 5xx" +
                                " | status={}", res.statusCode().value());
                        return Mono.error(new KhaltiException(
                                "Khalti server error — please try again later",
                                res.statusCode().value(), null));
                    })
                    .bodyToMono(KhaltiInitiateResponse.class)
                    .doOnNext(res -> log.info(
                            "[NepalPay Reactive] Khalti payment initiated" +
                                    " | pidx={} | orderId={}",
                            res.pidx(), finalRequest.purchaseOrderId()));

            return timedInitiate(mono.transform(m -> withRetry(m,
                    metrics != null ? metrics::incrementInitiateRetry : null)));
        });
    }

    /**
     * Lookup (verify) a Khalti payment — reactive.
     *
     * <p>Records {@code nepalpay.khalti.payment.lookup.duration}
     * when Micrometer is available.
     *
     * @param pidx The payment identifier from initiatePayment
     * @return Mono of verified payment details
     */
    public Mono<KhaltiLookupResponse> lookupPayment(String pidx) {
        if (pidx == null || pidx.isBlank()) {
            return Mono.error(
                    new KhaltiException("pidx cannot be null or blank"));
        }

        log.debug("[NepalPay Reactive] Khalti lookup | pidx={}", pidx);

        Mono<KhaltiLookupResponse> mono = webClient.post()
                .uri(LOOKUP_PATH)
                .bodyValue(Map.of("pidx", pidx))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] Khalti" +
                                                    " lookup 4xx | pidx={} | body={}",
                                            pidx, body);
                                    return Mono.error(new KhaltiException(
                                            "Khalti lookup failed — " +
                                                    "invalid pidx or unauthorized",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] Khalti lookup 5xx" +
                            " | pidx={}", pidx);
                    return Mono.error(new KhaltiException(
                            "Khalti server error during lookup — try again",
                            res.statusCode().value(), null));
                })
                .bodyToMono(KhaltiLookupResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] Khalti lookup result" +
                                " | pidx={} | status={} | success={}",
                        res.pidx(), res.status(),
                        res.isPaymentSuccessful()));

        return timedLookup(mono.transform(m -> withRetry(m,
                metrics != null ? metrics::incrementLookupRetry : null)));
    }

    /**
     * Full refund — reactive.
     *
     * <p>Records {@code nepalpay.khalti.payment.refund.duration}
     * when Micrometer is available.
     *
     * @param transactionId Khalti internal transaction ID (not pidx)
     * @return Mono of refund confirmation
     */
    public Mono<KhaltiRefundResponse> refundPayment(String transactionId) {
        return executeRefundRequest(transactionId, null);
    }

    /**
     * Partial refund — reactive.
     *
     * @param transactionId Khalti internal transaction ID
     * @param amountPaisa   Amount to refund in PAISA (NPR x 100)
     * @return Mono of refund confirmation
     */
    public Mono<KhaltiRefundResponse> refundPayment(
            String transactionId, Long amountPaisa) {

        if (amountPaisa != null && amountPaisa <= 0) {
            return Mono.error(new KhaltiException(
                    "amountPaisa must be greater than 0 for partial refund." +
                            " Got: " + amountPaisa +
                            ". Use refundPayment(transactionId) for full refund."));
        }
        return executeRefundRequest(transactionId, amountPaisa);
    }

    /** @return true if sandbox mode is active */
    public boolean isSandbox() { return props.sandbox(); }

    /** @return current API v2 base URL */
    public String baseUrl() { return baseUrl; }

    /** @return current base domain used for refund URL */
    public String baseDomain() { return baseDomain; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Refund execution
    // ─────────────────────────────────────────────────────────────────────

    private Mono<KhaltiRefundResponse> executeRefundRequest(
            String transactionId, Long amountPaisa) {

        if (transactionId == null || transactionId.isBlank()) {
            return Mono.error(new KhaltiException(
                    "transactionId cannot be null or blank. " +
                            "Obtain transactionId from lookupPayment() " +
                            "after payment reaches Completed."));
        }

        String refundUrl = baseDomain + REFUND_PATH_PREFIX
                + transactionId + REFUND_PATH_SUFFIX;
        Object requestBody = (amountPaisa != null)
                ? Map.of("amount", amountPaisa)
                : Map.of();

        log.debug("[NepalPay Reactive] Khalti refund | txnId={} | type={}",
                transactionId,
                amountPaisa != null
                        ? "PARTIAL (" + amountPaisa + " paisa)" : "FULL");

        Mono<KhaltiRefundResponse> mono = webClient.post()
                .uri(refundUrl)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] Khalti" +
                                                    " refund 4xx | txnId={} | body={}",
                                            transactionId, body);
                                    return Mono.error(new KhaltiException(
                                            "Khalti refund failed — " +
                                                    "Only Completed payments can be refunded.",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] Khalti refund 5xx" +
                            " | txnId={}", transactionId);
                    return Mono.error(new KhaltiException(
                            "Khalti server error during refund — try again",
                            res.statusCode().value(), null));
                })
                .bodyToMono(KhaltiRefundResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] Khalti refund result" +
                                " | txnId={} | success={}",
                        transactionId, res.isRefundSuccessful()));

        return timedRefund(mono.transform(m -> withRetry(m,
                metrics != null ? metrics::incrementRefundRetry : null)));
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Reactive timer helpers
    // ─────────────────────────────────────────────────────────────────────

    private <T> Mono<T> timedInitiate(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(metrics.initiateSuccessTimer()))
                    .doOnError(e -> sample.stop(metrics.initiateErrorTimer()));
        });
    }

    private <T> Mono<T> timedLookup(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(metrics.lookupSuccessTimer()))
                    .doOnError(e -> sample.stop(metrics.lookupErrorTimer()));
        });
    }

    private <T> Mono<T> timedRefund(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(metrics.refundSuccessTimer()))
                    .doOnError(e -> sample.stop(metrics.refundErrorTimer()));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Retry
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Applies Reactor retry with exponential backoff.
     *
     * <p><strong>Fix (D-53):</strong> Accepts a per-call-site
     * {@code retryIncrement} so initiate/lookup/refund retries each
     * increment their own counter instead of always incrementing initiate.
     *
     * <p>Retries on:
     * <ul>
     *   <li>{@link WebClientRequestException} — network failures</li>
     *   <li>{@link KhaltiException} with httpStatus = 0 or >= 500</li>
     * </ul>
     * Never retries 4xx.
     *
     * @param source          the Mono to apply retry to
     * @param retryIncrement  per-operation retry counter — null = no counter
     */
    private <T> Mono<T> withRetry(Mono<T> source, Runnable retryIncrement) {
        if (!retryProps.isActive()) return source;

        return source.retryWhen(
                Retry.backoff(
                                retryProps.maxAttempts(),
                                Duration.ofMillis(retryProps.initialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryProps.maxDelayMs()))
                        .jitter(0.1)
                        .filter(throwable ->
                                throwable instanceof WebClientRequestException
                                        || (throwable instanceof KhaltiException ke
                                        && (ke.httpStatus() == 0
                                        || ke.httpStatus() >= 500)))
                        .doBeforeRetry(signal -> {
                            log.warn("[NepalPay Reactive] Khalti retry" +
                                            " attempt {} | cause={}",
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage());
                            if (retryIncrement != null) {
                                retryIncrement.run();
                            }
                        })
        ).onErrorMap(
                Exceptions::isRetryExhausted,
                Throwable::getCause
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Validation
    // ─────────────────────────────────────────────────────────────────────

    private void validateInitiateRequest(KhaltiInitiateRequest req) {
        if (req == null) {
            throw new KhaltiException(
                    "KhaltiInitiateRequest cannot be null");
        }
        if (req.amount() <= 0) {
            throw new KhaltiException(
                    "Amount must be greater than 0 paisa. Got: "
                            + req.amount());
        }
        if (req.purchaseOrderId() == null
                || req.purchaseOrderId().isBlank()) {
            throw new KhaltiException(
                    "purchaseOrderId is required and cannot be blank");
        }
        if (req.purchaseOrderName() == null
                || req.purchaseOrderName().isBlank()) {
            throw new KhaltiException(
                    "purchaseOrderName is required and cannot be blank");
        }
    }

    private KhaltiInitiateRequest withDefaults(KhaltiInitiateRequest req) {
        String returnUrl  = req.returnUrl()  != null
                ? req.returnUrl()  : props.returnUrl();
        String websiteUrl = req.websiteUrl() != null
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
                .customerInfo(
                        req.customerInfo() != null
                                ? req.customerInfo().name()  : null,
                        req.customerInfo() != null
                                ? req.customerInfo().email() : null,
                        req.customerInfo() != null
                                ? req.customerInfo().phone() : null)
                .build();
    }
}