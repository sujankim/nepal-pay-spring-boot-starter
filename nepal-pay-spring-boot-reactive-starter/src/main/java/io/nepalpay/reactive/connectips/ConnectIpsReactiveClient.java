package io.nepalpay.reactive.connectips;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.core.retry.RetryProperties;
import io.nepalpay.core.metrics.ConnectIpsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Enumeration;

/**
 * Reactive ConnectIPS Payment Gateway Client.
 *
 * <p>Uses Spring {@link WebClient} and returns {@link Mono} responses.
 * Drop-in reactive replacement for the blocking {@code ConnectIpsClient}.
 *
 * <p><strong>Timeout:</strong>
 * Configurable via {@code nepalpay.connectips.timeout-seconds}.
 * Default: 30 seconds. Applied as Reactor Netty
 * {@code HttpClient.responseTimeout()} — fully non-blocking.
 *
 * <p><strong>Metrics:</strong>
 * If constructed with a {@link MeterRegistry}, validateTransaction()
 * is timed via {@link Timer.Sample} + reactive doOnSuccess/doOnError.
 * Timer accessors are exposed by {@link ConnectIpsMetrics}.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsReactiveClient {

    // ── URLs ──────────────────────────────────────────────────────────────
    private static final String UAT_GATEWAY_URL    =
            "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL   =
            "https://connectips.com/connectipswebgw/loginpage";
    private static final String UAT_VALIDATE_BASE  =
            "https://uat.connectips.com";
    private static final String PROD_VALIDATE_BASE =
            "https://connectips.com";
    private static final String VALIDATE_PATH      =
            "/connectipswebws/api/creditor/validatetxn";

    // ── Constants ─────────────────────────────────────────────────────────
    private static final String            CURRENCY     = "NPR";
    private static final String            TOKEN_SUFFIX = "TOKEN=TOKEN";
    private static final DateTimeFormatter DATE_FORMAT  =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Default HTTP timeout for ConnectIPS API calls.
     *
     * <p>30 seconds is intentionally longer than Khalti/eSewa's 10s default.
     * ConnectIPS validates bank transfers via NCHL — bank systems can
     * legitimately be slower than commercial payment gateway APIs.
     *
     * <p>Configurable via {@code nepalpay.connectips.timeout-seconds}.
     * This constant is the fallback used by the test-only PrivateKey
     * constructors that do not accept a timeoutSeconds parameter.
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // ── Fields ────────────────────────────────────────────────────────────
    private final int             merchantId;
    private final String          appId;
    private final String          appName;
    private final String          appPassword;
    private final boolean         sandbox;
    private final String          formActionUrl;
    private final WebClient       webClient;
    private final RetryProperties retryProps;
    private final PrivateKey      privateKey;
    private final int             timeoutSeconds;

    /**
     * Optional Micrometer metrics — null when Actuator not on classpath.
     * All usages guarded with null check — no NPE possible.
     */
    private final ConnectIpsMetrics metrics;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS — Public
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — no metrics, DEFAULT retry, default timeout.
     * Used by auto-configuration when Actuator is absent.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder) {

        this(merchantId, appId, appName, appPassword,
                loadPrivateKey(pfxBytes, pfxPassword),
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                RetryProperties.DEFAULT, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Constructor with explicit retry — no metrics, default timeout.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, RetryProperties retry) {

        this(merchantId, appId, appName, appPassword,
                loadPrivateKey(pfxBytes, pfxPassword),
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Constructor with explicit retry + optional metrics + default timeout.
     *
     * <p>Used by {@code NepalPayReactiveMetricsAutoConfiguration} when
     * Actuator is on the classpath.
     *
     * @param retry         retry configuration
     * @param meterRegistry Micrometer registry — null = no metrics
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, RetryProperties retry,
            MeterRegistry meterRegistry) {

        this(merchantId, appId, appName, appPassword,
                loadPrivateKey(pfxBytes, pfxPassword),
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry, meterRegistry, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Constructor with explicit retry + optional metrics + configurable timeout.
     *
     * <p><strong>Fix (Issue #8):</strong> Exposes
     * {@code nepalpay.connectips.timeout-seconds} as a constructor parameter
     * so auto-configuration can pass the user-configured value.
     *
     * <p>Timeout is applied as Reactor Netty
     * {@code HttpClient.responseTimeout()} — fully non-blocking.
     *
     * @param retry          retry configuration
     * @param meterRegistry  Micrometer registry — null = no metrics
     * @param timeoutSeconds HTTP response timeout in seconds
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, RetryProperties retry,
            MeterRegistry meterRegistry, int timeoutSeconds) {

        this(merchantId, appId, appName, appPassword,
                loadPrivateKey(pfxBytes, pfxPassword),
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry, meterRegistry, timeoutSeconds);
    }

    /**
     * Test / proxy constructor — custom validate base URL, no metrics,
     * DEFAULT retry, default timeout.
     *
     * <p><strong>Fix (D-48):</strong> Reintroduced to avoid breaking
     * downstream code that was using a custom validate URL (e.g. tests
     * or proxy setups). Previously this was removed accidentally when
     * the constructors were refactored — flagged by Copilot review.
     *
     * @param validateBaseUrlOverride Custom validate API base URL
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride) {

        this(merchantId, appId, appName, appPassword,
                loadPrivateKey(pfxBytes, pfxPassword),
                sandbox, builder, validateBaseUrlOverride,
                RetryProperties.DEFAULT, null, DEFAULT_TIMEOUT_SECONDS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS — Package-private (test-only)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Test-only constructor — accepts pre-built PrivateKey directly.
     * Default timeout (30s). No metrics.
     * Package-private — only test code in the same package can use it.
     */
    ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this(merchantId, appId, appName, appPassword,
                privateKey, sandbox, builder, validateBaseUrlOverride,
                retry, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Test-only constructor — accepts pre-built PrivateKey + explicit timeout.
     * No metrics. Package-private — only test code in the same package can
     * use it.
     *
     * <p>Used by timeout tests that need to verify
     * {@code timeoutSeconds} propagation and timeout behavior
     * without needing a real PKCS12 file.
     *
     * @param retry          retry configuration — null = DEFAULT
     * @param timeoutSeconds HTTP response timeout in seconds
     */
    ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry, int timeoutSeconds) {

        this(merchantId, appId, appName, appPassword,
                privateKey, sandbox, builder, validateBaseUrlOverride,
                retry, null, timeoutSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CORE PRIVATE CONSTRUCTOR — single point of initialization
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Core private constructor — all other constructors delegate here.
     */
    private ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry, MeterRegistry meterRegistry,
            int timeoutSeconds) {

        this.merchantId     = merchantId;
        this.appId          = appId;
        this.appName        = appName;
        this.appPassword    = appPassword;
        this.sandbox        = sandbox;
        this.formActionUrl  = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps     = retry != null ? retry : RetryProperties.DEFAULT;
        this.privateKey     = privateKey;

        // ── Validate timeout ──────────────────────────────────────────────
        // Fix: ensure timeoutSeconds > 0, throw meaningful exception
        if (timeoutSeconds <= 0) {
            throw new ConnectIpsException(
                    "ConnectIPS timeoutSeconds must be greater than 0. Got: "
                            + timeoutSeconds +
                            ". Set nepalpay.connectips.timeout-seconds in application.yml.");
        }
        this.timeoutSeconds = timeoutSeconds;

        // ── Metrics — optional ────────────────────────────────────────────
        this.metrics = (meterRegistry != null)
                ? new ConnectIpsMetrics(meterRegistry, sandbox)
                : null;

        // ── WebClient with configurable timeout ───────────────────────────
        // Uses ReactorClientHttpConnector + Netty HttpClient.responseTimeout()
        // — correct non-blocking approach for WebFlux (no Thread.sleep).
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(this.timeoutSeconds));

        this.webClient = builder
                .baseUrl(validateBaseUrlOverride)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay Reactive] ConnectIpsReactiveClient initialized" +
                        " | mode={} | merchantId={} | timeout={}s" +
                        " | retry={} | metrics={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId,
                this.timeoutSeconds,
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build signed ConnectIPS form payload — synchronous (no HTTP).
     *
     * @param request payment request (must not be null)
     * @return signed form payload
     * @throws ConnectIpsException if request is null or RSA signing fails
     */
    public ConnectIpsFormPayload buildFormPayload(
            ConnectIpsPaymentRequest request) {

        if (request == null) {
            throw new ConnectIpsException(
                    "ConnectIpsPaymentRequest cannot be null");
        }
        return buildFormPayload(
                request.txnId(), request.txnAmtPaisa(),
                request.referenceId(), request.remarks(),
                request.particulars());
    }

    /**
     * Build signed ConnectIPS form payload — synchronous (no HTTP).
     *
     * @param txnId       unique transaction ID
     * @param txnAmtPaisa amount in PAISA (NPR x 100)
     * @param referenceId order reference ID
     * @param remarks     optional remarks
     * @param particulars optional particulars
     * @return signed form payload
     * @throws ConnectIpsException if inputs invalid or RSA signing fails
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId, long txnAmtPaisa, String referenceId,
            String remarks, String particulars) {

        validatePaymentInput(txnId, referenceId, txnAmtPaisa);

        String txnDate         = LocalDate.now().format(DATE_FORMAT);
        String safeRemarks     = remarks     != null ? remarks     : "";
        String safeParticulars = particulars != null ? particulars : "";
        String message = buildTokenMessage(
                txnId, txnDate, txnAmtPaisa,
                referenceId, safeRemarks, safeParticulars);
        String token = generateRsaToken(message);

        return new ConnectIpsFormPayload(
                merchantId, appId, appName, txnId, txnDate,
                CURRENCY, txnAmtPaisa, referenceId,
                safeRemarks, safeParticulars, token, formActionUrl);
    }

    /**
     * Validate a ConnectIPS transaction after payment — reactive.
     *
     * <p>Both input validation and RSA signing are wrapped inside
     * {@link Mono#defer} so any {@link ConnectIpsException} is emitted
     * as a reactive error signal — never thrown outside the pipeline.
     *
     * <p>Records {@code nepalpay.connectips.validate.duration} timer
     * when Micrometer is available.
     *
     * @param txnId       transaction ID from buildFormPayload
     * @param referenceId original reference/order ID
     * @param txnAmtPaisa amount in PAISA
     * @return Mono of validation response
     */
    public Mono<ConnectIpsValidateResponse> validateTransaction(
            String txnId, String referenceId, long txnAmtPaisa) {

        return Mono.defer(() -> {
            try {
                validatePaymentInput(txnId, referenceId, txnAmtPaisa);
            } catch (ConnectIpsException e) {
                return Mono.error(e);
            }

            String txnDate = LocalDate.now().format(DATE_FORMAT);
            String message = buildTokenMessage(
                    txnId, txnDate, txnAmtPaisa, referenceId, "", "");

            String token;
            try {
                token = generateRsaToken(message);
            } catch (ConnectIpsException e) {
                return Mono.error(e);
            }

            log.debug("[NepalPay Reactive] ConnectIPS validating" +
                    " | txnId={}", txnId);

            Mono<ConnectIpsValidateResponse> mono =
                    executeValidateRequest(referenceId, txnAmtPaisa, token);

            return timedValidate(mono);
        });
    }

    /**
     * Validate using pre-built token — test-friendly, skips RSA signing.
     *
     * @param referenceId your reference ID
     * @param txnAmtPaisa amount in PAISA
     * @param token       pre-built token
     * @return Mono of validation response
     */
    public Mono<ConnectIpsValidateResponse> validateTransactionWithToken(
            String referenceId, long txnAmtPaisa, String token) {

        log.debug("[NepalPay Reactive] ConnectIPS validating with token" +
                " | refId={}", referenceId);
        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /** @return form action URL (UAT or production) */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if sandbox/UAT mode */
    public boolean isSandbox() { return sandbox; }

    /** @return configured HTTP timeout in seconds */
    public int timeoutSeconds() { return timeoutSeconds; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Reactive timer helper
    // ─────────────────────────────────────────────────────────────────────

    private <T> Mono<T> timedValidate(Mono<T> source) {
        if (metrics == null) return source;
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start();
            return source
                    .doOnSuccess(v -> sample.stop(
                            metrics.validateSuccessTimer()))
                    .doOnError(e -> sample.stop(
                            metrics.validateErrorTimer()));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — HTTP execution
    // ─────────────────────────────────────────────────────────────────────

    private Mono<ConnectIpsValidateResponse> executeValidateRequest(
            String referenceId, long txnAmtPaisa, String token) {

        ConnectIpsValidateRequest request = new ConnectIpsValidateRequest(
                merchantId, appId, referenceId, txnAmtPaisa, token);

        String basicAuth = Base64.getEncoder().encodeToString(
                (appId + ":" + appPassword)
                        .getBytes(StandardCharsets.UTF_8));

        return webClient.post()
                .uri(VALIDATE_PATH)
                .header("Authorization", "Basic " + basicAuth)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] ConnectIPS" +
                                                    " validate 4xx | refId={} | body={}",
                                            referenceId, body);
                                    return Mono.error(new ConnectIpsException(
                                            "ConnectIPS validation failed",
                                            res.statusCode().value(), body));
                                }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> {
                    log.error("[NepalPay Reactive] ConnectIPS validate 5xx" +
                            " | refId={}", referenceId);
                    return Mono.error(new ConnectIpsException(
                            "ConnectIPS server error during validation",
                            res.statusCode().value(), null));
                })
                .bodyToMono(ConnectIpsValidateResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] ConnectIPS validate result" +
                                " | refId={} | success={}",
                        referenceId, res.isPaymentSuccessful()))
                .transform(this::withRetry);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Retry
    // ─────────────────────────────────────────────────────────────────────

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
                                        || (t instanceof ConnectIpsException ce
                                        && (ce.httpStatus() == 0
                                        || ce.httpStatus() >= 500)))
                        .doBeforeRetry(s -> {
                            log.warn("[NepalPay Reactive] ConnectIPS retry" +
                                            " attempt {}",
                                    s.totalRetries() + 1);
                            if (metrics != null) {
                                metrics.incrementValidateRetry();
                            }
                        })
        ).onErrorMap(
                Exceptions::isRetryExhausted,
                Throwable::getCause
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static void validatePaymentInput(
            String txnId, String referenceId, long txnAmtPaisa) {

        if (txnId == null || txnId.isBlank()) {
            throw new ConnectIpsException(
                    "txnId is required and cannot be blank");
        }
        if (referenceId == null || referenceId.isBlank()) {
            throw new ConnectIpsException(
                    "referenceId is required and cannot be blank");
        }
        if (txnAmtPaisa <= 0) {
            throw new ConnectIpsException(
                    "txnAmtPaisa must be greater than 0. Got: "
                            + txnAmtPaisa);
        }
    }

    /**
     * Load and extract RSA PrivateKey from a PKCS12 (.pfx) file.
     *
     * <p>Iterates all KeyStore aliases and checks {@code isKeyEntry()}
     * before selecting — safe for PFX files with multiple entries
     * (certificate chain + private key).
     */
    private static PrivateKey loadPrivateKey(
            byte[] pfxBytes, String pfxPassword) {

        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx certificate bytes are empty. " +
                            "Set nepalpay.connectips.pfx-path in application.yml.");
        }
        if (pfxPassword == null || pfxPassword.isBlank()) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx password not configured. " +
                            "Set nepalpay.connectips.pfx-password in application.yml.");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(pfxBytes),
                    pfxPassword.toCharArray());

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.isKeyEntry(alias)) continue;
                Object key = keyStore.getKey(
                        alias, pfxPassword.toCharArray());
                if (key instanceof PrivateKey pk) return pk;
            }
            throw new ConnectIpsException(
                    "ConnectIPS .pfx does not contain a private key entry.");
        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS RSA private key: "
                            + e.getMessage(), e);
        }
    }

    private String buildTokenMessage(
            String txnId, String txnDate, long txnAmtPaisa,
            String referenceId, String remarks, String particulars) {

        return "MERCHANTID=" + merchantId
                + ",APPID="       + appId
                + ",APPNAME="     + appName
                + ",TXNID="       + txnId
                + ",TXNDATE="     + txnDate
                + ",TXNCRNCY="    + CURRENCY
                + ",TXNAMT="      + txnAmtPaisa
                + ",REFERENCEID=" + referenceId
                + ",REMARKS="     + remarks
                + ",PARTICULARS=" + particulars
                + ","             + TOKEN_SUFFIX;
    }

    private String generateRsaToken(String message) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(this.privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "ConnectIPS RSA signing failed: " + e.getMessage(), e);
        }
    }
}