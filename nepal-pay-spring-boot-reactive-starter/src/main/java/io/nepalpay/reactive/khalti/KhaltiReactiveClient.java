package io.nepalpay.reactive.khalti;

import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.reactive.config.NepalPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
 * <p>Provides:
 * <ul>
 *   <li>{@link #initiatePayment}  — Start payment, get redirect URL</li>
 *   <li>{@link #lookupPayment}    — Verify payment after callback</li>
 *   <li>{@link #refundPayment}    — Full refund</li>
 *   <li>{@link #refundPayment(String, Long)} — Partial refund</li>
 * </ul>
 *
 * <p>Usage in a reactive controller:
 * <pre>{@code
 * @PostMapping("/khalti/initiate")
 * public Mono<ResponseEntity<Map<String, Object>>> initiate(
 *         @RequestBody PaymentRequest req) {
 *
 *     return khaltiReactiveClient
 *         .initiatePayment(buildRequest(req))
 *         .map(res -> ResponseEntity.ok(Map.of(
 *             "pidx",        res.pidx(),
 *             "payment_url", res.paymentUrl()
 *         )))
 *         .onErrorResume(KhaltiException.class, e ->
 *             Mono.just(ResponseEntity.badRequest().body(
 *                 Map.of("error", e.getMessage())
 *             ))
 *         );
 * }
 * }</pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiReactiveClient {

    // ── Official Khalti API base URLs ─────────────────────────────────────
    private static final String SANDBOX_BASE_URL    = "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL = "https://khalti.com/api/v2";
    private static final String SANDBOX_BASE_DOMAIN    = "https://dev.khalti.com";
    private static final String PRODUCTION_BASE_DOMAIN = "https://khalti.com";

    // ── Endpoint paths ────────────────────────────────────────────────────
    private static final String INITIATE_PATH      = "/epayment/initiate/";
    private static final String LOOKUP_PATH        = "/epayment/lookup/";
    private static final String REFUND_PATH_PREFIX = "/api/merchant-transaction/";
    private static final String REFUND_PATH_SUFFIX = "/refund/";

    // ── Fields ────────────────────────────────────────────────────────────
    private final NepalPayProperties.KhaltiProperties props;
    private final WebClient webClient;
    private final String baseUrl;
    private final String baseDomain;
    private final RetryProperties retryProps;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     *
     * @param props          Khalti properties from application.yml
     * @param webClientBuilder Spring WebClient builder
     */
    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder) {

        this(props, webClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN);
    }

    /**
     * Test constructor — allows injecting a custom base URL.
     *
     * @param props             Khalti properties
     * @param webClientBuilder  WebClient builder
     * @param baseUrlOverride   Custom base URL (MockWebServer URL in tests)
     */
    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder,
            String baseUrlOverride) {

        this(props, webClientBuilder,
                baseUrlOverride,
                baseUrlOverride.endsWith("/")
                        ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1)
                        : baseUrlOverride);
    }

    /**
     * Core private constructor.
     */
    private KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder,
            String baseUrl,
            String baseDomain) {

        this.props      = props;
        this.baseUrl    = baseUrl;
        this.baseDomain = baseDomain;
        this.retryProps = props.retryOrDefault();

        this.webClient = webClientBuilder
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Key " + props.secretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay Reactive] KhaltiReactiveClient initialized" +
                        " | mode={} | baseUrl={} | retry={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                this.baseUrl,
                this.retryProps.summary());
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment — reactive.
     *
     * @param request Payment details
     * @return {@link Mono} emitting the response, or error signal on failure
     */
    public Mono<KhaltiInitiateResponse> initiatePayment(
            KhaltiInitiateRequest request) {

        validateInitiateRequest(request);
        KhaltiInitiateRequest finalRequest = withDefaults(request);

        log.debug("[NepalPay Reactive] Khalti initiate | orderId={} | amount={}",
                finalRequest.purchaseOrderId(), finalRequest.amount());

        return webClient.post()
                .uri(INITIATE_PATH)
                .bodyValue(finalRequest)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] Khalti initiate" +
                                                    " 4xx | status={} | body={}",
                                            res.statusCode().value(), body);
                                    return Mono.error(new KhaltiException(
                                            "Khalti payment initiation failed — " +
                                                    "check your secret key or request",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] Khalti initiate 5xx | status={}",
                            res.statusCode().value());
                    return Mono.error(new KhaltiException(
                            "Khalti server error — please try again later",
                            res.statusCode().value(), null));
                })
                .bodyToMono(KhaltiInitiateResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] Khalti payment initiated" +
                                " | pidx={} | orderId={}",
                        res.pidx(), finalRequest.purchaseOrderId()))
                .transform(this::withRetry);
    }

    /**
     * Lookup (verify) a Khalti payment — reactive.
     *
     * @param pidx Payment identifier from {@link #initiatePayment}
     * @return {@link Mono} emitting lookup response
     */
    public Mono<KhaltiLookupResponse> lookupPayment(String pidx) {
        if (pidx == null || pidx.isBlank()) {
            return Mono.error(
                    new KhaltiException("pidx cannot be null or blank"));
        }

        log.debug("[NepalPay Reactive] Khalti lookup | pidx={}", pidx);

        return webClient.post()
                .uri(LOOKUP_PATH)
                .bodyValue(Map.of("pidx", pidx))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] Khalti lookup" +
                                                    " 4xx | pidx={} | body={}",
                                            pidx, body);
                                    return Mono.error(new KhaltiException(
                                            "Khalti lookup failed — " +
                                                    "invalid pidx or unauthorized",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] Khalti lookup 5xx | pidx={}",
                            pidx);
                    return Mono.error(new KhaltiException(
                            "Khalti server error during lookup — try again",
                            res.statusCode().value(), null));
                })
                .bodyToMono(KhaltiLookupResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] Khalti lookup result" +
                                " | pidx={} | status={} | success={}",
                        res.pidx(), res.status(), res.isPaymentSuccessful()))
                .transform(this::withRetry);
    }

    /**
     * Full refund — reactive.
     *
     * @param transactionId Khalti transaction ID from lookupPayment()
     * @return {@link Mono} emitting refund response
     */
    public Mono<KhaltiRefundResponse> refundPayment(String transactionId) {
        return executeRefundRequest(transactionId, null);
    }

    /**
     * Partial refund — reactive.
     *
     * @param transactionId Khalti transaction ID from lookupPayment()
     * @param amountPaisa   Amount to refund in paisa
     * @return {@link Mono} emitting refund response
     */
    public Mono<KhaltiRefundResponse> refundPayment(
            String transactionId, Long amountPaisa) {

        if (amountPaisa != null && amountPaisa <= 0) {
            return Mono.error(new KhaltiException(
                    "amountPaisa must be greater than 0 for partial refund. Got: "
                            + amountPaisa
                            + ". Use refundPayment(transactionId) for full refund."));
        }
        return executeRefundRequest(transactionId, amountPaisa);
    }

    /** @return true if sandbox mode is active */
    public boolean isSandbox() { return props.sandbox(); }

    /** @return current API v2 base URL */
    public String baseUrl() { return baseUrl; }

    /** @return current base domain (used for refund URL) */
    public String baseDomain() { return baseDomain; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private Mono<KhaltiRefundResponse> executeRefundRequest(
            String transactionId, Long amountPaisa) {

        if (transactionId == null || transactionId.isBlank()) {
            return Mono.error(new KhaltiException(
                    "transactionId cannot be null or blank. " +
                            "Obtain transactionId from lookupPayment() after " +
                            "payment reaches Completed."));
        }

        String refundUrl = baseDomain + REFUND_PATH_PREFIX
                + transactionId + REFUND_PATH_SUFFIX;

        Object requestBody = (amountPaisa != null)
                ? Map.of("amount", amountPaisa)
                : Map.of();

        log.debug("[NepalPay Reactive] Khalti refund | txnId={} | type={}",
                transactionId,
                amountPaisa != null ? "PARTIAL (" + amountPaisa + ")" : "FULL");

        return webClient.post()
                .uri(refundUrl)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] Khalti refund" +
                                                    " 4xx | txnId={} | body={}",
                                            transactionId, body);
                                    return Mono.error(new KhaltiException(
                                            "Khalti refund failed — " +
                                                    "Only Completed payments can be refunded.",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] Khalti refund 5xx | txnId={}",
                            transactionId);
                    return Mono.error(new KhaltiException(
                            "Khalti server error during refund — try again",
                            res.statusCode().value(), null));
                })
                .bodyToMono(KhaltiRefundResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] Khalti refund result" +
                                " | txnId={} | success={}",
                        transactionId, res.isRefundSuccessful()))
                .transform(this::withRetry);
    }

    /**
     * Applies retry logic using Reactor's {@link Retry}.
     *
     * <p>Reactor retry differs from the manual loop in blocking clients:
     * <ul>
     *   <li>Uses {@code .retryWhen()} operator on the Mono pipeline</li>
     *   <li>{@code Retry.backoff()} provides exponential backoff built-in</li>
     *   <li>Only retries on {@link KhaltiException} with non-4xx status</li>
     * </ul>
     *
     * @param source the Mono to apply retry to
     * @return Mono with retry applied, or unchanged if retry is disabled
     */
    private <T> Mono<T> withRetry(Mono<T> source) {
        if (!retryProps.isActive()) {
            return source;
        }

        return source.retryWhen(
                Retry.backoff(
                                retryProps.maxAttempts(),
                                Duration.ofMillis(retryProps.initialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryProps.maxDelayMs()))
                        .jitter(0.1)   // ← 10% jitter — matches RetryProperties.jitter()
                        .filter(throwable ->
                                // Only retry non-4xx KhaltiExceptions
                                throwable instanceof KhaltiException ke
                                        && (ke.httpStatus() == 0
                                        || ke.httpStatus() >= 500))
                        .doBeforeRetry(signal ->
                                log.warn("[NepalPay Reactive] Khalti retry" +
                                                " attempt {} | cause={}",
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage()))
        ).onErrorMap(
                // Reactor wraps retry exhaustion in RetryExhaustedException —
                // unwrap to our KhaltiException so callers get consistent types
                ex -> ex instanceof reactor.util.retry.Retry.RetryExhaustedException
                        ? ex.getCause()
                        : ex
        );
    }

    private void validateInitiateRequest(KhaltiInitiateRequest req) {
        if (req == null) {
            throw new KhaltiException("KhaltiInitiateRequest cannot be null");
        }
        if (req.amount() <= 0) {
            throw new KhaltiException(
                    "Amount must be greater than 0 paisa. Got: " + req.amount());
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
        String returnUrl  = req.returnUrl()  != null ? req.returnUrl()  : props.returnUrl();
        String websiteUrl = req.websiteUrl() != null ? req.websiteUrl() : props.websiteUrl();

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
}