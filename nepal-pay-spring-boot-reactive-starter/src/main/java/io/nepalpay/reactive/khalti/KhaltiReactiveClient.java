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
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiReactiveClient {

    private static final String SANDBOX_BASE_URL       = "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL    = "https://khalti.com/api/v2";
    private static final String SANDBOX_BASE_DOMAIN    = "https://dev.khalti.com";
    private static final String PRODUCTION_BASE_DOMAIN = "https://khalti.com";

    private static final String INITIATE_PATH      = "/epayment/initiate/";
    private static final String LOOKUP_PATH        = "/epayment/lookup/";
    private static final String REFUND_PATH_PREFIX = "/api/merchant-transaction/";
    private static final String REFUND_PATH_SUFFIX = "/refund/";

    private final NepalPayProperties.KhaltiProperties props;
    private final WebClient webClient;
    private final String baseUrl;
    private final String baseDomain;
    private final RetryProperties retryProps;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    public KhaltiReactiveClient(
            NepalPayProperties.KhaltiProperties props,
            WebClient.Builder webClientBuilder) {

        this(props, webClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN);
    }

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
     * <p>Wrapped in {@link Mono#defer} so validation errors are also
     * delivered as reactive error signals — callers can handle all
     * errors uniformly via {@code onErrorResume} / {@code StepVerifier}.
     */
    public Mono<KhaltiInitiateResponse> initiatePayment(
            KhaltiInitiateRequest request) {

        // Mono.defer — validation runs lazily on subscription.
        // This ensures errors are Mono signals, not thrown exceptions,
        // so callers get consistent reactive error handling.
        return Mono.defer(() -> {
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
        });
    }

    /**
     * Lookup (verify) a Khalti payment — reactive.
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
     */
    public Mono<KhaltiRefundResponse> refundPayment(String transactionId) {
        return executeRefundRequest(transactionId, null);
    }

    /**
     * Partial refund — reactive.
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
     * Applies retry using Reactor's {@link Retry#backoff}.
     *
     * <p>Retries on:
     * <ul>
     *   <li>{@link WebClientRequestException} — network/transport failures</li>
     *   <li>{@link KhaltiException} with httpStatus=0 or >=500</li>
     * </ul>
     *
     * <p>Uses {@link Exceptions#isRetryExhausted} to unwrap exhausted
     * retry wrapper — callers receive the original {@link KhaltiException}.
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
                        .jitter(0.1)
                        .filter(throwable ->
                                // Retry WebClient transport failures (connection, timeout)
                                throwable instanceof WebClientRequestException
                                        // Retry 5xx and network errors wrapped as KhaltiException
                                        || (throwable instanceof KhaltiException ke
                                        && (ke.httpStatus() == 0
                                        || ke.httpStatus() >= 500)))
                        .doBeforeRetry(signal ->
                                log.warn("[NepalPay Reactive] Khalti retry" +
                                                " attempt {} | cause={}",
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage()))
        ).onErrorMap(
                // Unwrap Reactor's retry-exhausted wrapper → original exception
                // Use reactor.core.Exceptions.isRetryExhausted (correct API)
                Exceptions::isRetryExhausted,
                Throwable::getCause
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
                // preserve customerInfo from original request
                .customerInfo(
                        req.customerInfo() != null
                                ? req.customerInfo().name()
                                : null,
                        req.customerInfo() != null
                                ? req.customerInfo().email()
                                : null,
                        req.customerInfo() != null
                                ? req.customerInfo().phone()
                                : null)
                .build();
    }
}