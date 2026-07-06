package io.nepalpay.reactive.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.core.retry.RetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
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
 * <p>ConnectIPS is operated by Nepal Clearing House Ltd (NCHL).
 * Requires merchant registration — contact connectips@nchl.com.np.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #buildFormPayload} — RSA-signed form payload (synchronous)</li>
 *   <li>{@link #validateTransaction} — Server-side payment verification (reactive)</li>
 *   <li>{@link #validateTransactionWithToken} — Test-friendly verification (reactive)</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsReactiveClient {

    private static final String UAT_GATEWAY_URL    =
            "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL   =
            "https://connectips.com/connectipswebgw/loginpage";
    private static final String UAT_VALIDATE_BASE  = "https://uat.connectips.com";
    private static final String PROD_VALIDATE_BASE = "https://connectips.com";
    private static final String VALIDATE_PATH      =
            "/connectipswebws/api/creditor/validatetxn";

    private static final String            CURRENCY     = "NPR";
    private static final String            TOKEN_SUFFIX = "TOKEN=TOKEN";
    private static final DateTimeFormatter DATE_FORMAT  =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final int             merchantId;
    private final String          appId;
    private final String          appName;
    private final String          appPassword;
    private final boolean         sandbox;
    private final String          formActionUrl;
    private final WebClient       webClient;
    private final RetryProperties retryProps;
    private final PrivateKey      privateKey;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE);
    }

    /**
     * Test constructor — custom validate URL, DEFAULT retry.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder, validateBaseUrlOverride, RetryProperties.DEFAULT);
    }

    /**
     * Production constructor with explicit retry.
     * Used by auto-configuration when retry is configured.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, RetryProperties retry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE, retry);
    }

    /**
     * Full constructor — custom validate URL + explicit retry.
     */
    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this.merchantId    = merchantId;
        this.appId         = appId;
        this.appName       = appName;
        this.appPassword   = appPassword;
        this.sandbox       = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps    = retry != null ? retry : RetryProperties.DEFAULT;
        this.privateKey    = loadPrivateKey(pfxBytes, pfxPassword);

        this.webClient = builder
                .baseUrl(validateBaseUrlOverride)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay Reactive] ConnectIpsReactiveClient initialized" +
                        " | mode={} | merchantId={} | retry={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId,
                this.retryProps.summary());
    }

    /**
     * Test-only constructor — accepts pre-built PrivateKey directly.
     *
     * <p>Avoids needing a real .pfx file in tests. Package-private so only
     * test code in the same package can use it.
     */
    ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this.merchantId    = merchantId;
        this.appId         = appId;
        this.appName       = appName;
        this.appPassword   = appPassword;
        this.privateKey    = privateKey;
        this.sandbox       = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps    = retry != null ? retry : RetryProperties.DEFAULT;

        this.webClient = builder
                .baseUrl(validateBaseUrlOverride)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build signed ConnectIPS form payload — synchronous (no HTTP call).
     *
     * <p>Validates the request before performing RSA signing.
     *
     * @param request payment request (must not be null)
     * @return signed form payload
     * @throws ConnectIpsException if request is null, inputs are invalid,
     *                             or RSA signing fails
     */
    public ConnectIpsFormPayload buildFormPayload(ConnectIpsPaymentRequest request) {
        if (request == null) {
            throw new ConnectIpsException(
                    "ConnectIpsPaymentRequest cannot be null");
        }
        return buildFormPayload(
                request.txnId(), request.txnAmtPaisa(),
                request.referenceId(), request.remarks(), request.particulars());
    }

    /**
     * Build signed ConnectIPS form payload — synchronous (no HTTP call).
     *
     * @param txnId       unique transaction ID
     * @param txnAmtPaisa transaction amount in PAISA (NPR × 100)
     * @param referenceId your order or reference ID
     * @param remarks     optional remarks
     * @param particulars optional particulars
     * @return signed form payload
     * @throws ConnectIpsException if inputs are invalid or RSA signing fails
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
     * <p><strong>Reactive contract:</strong> Both input validation
     * ({@code validatePaymentInput}) and RSA token generation
     * ({@code generateRsaToken}) are wrapped inside {@link Mono#defer}
     * so any {@link ConnectIpsException} they throw is emitted as a
     * proper Mono error signal — callers can handle all errors uniformly
     * via {@code onErrorResume} / {@code StepVerifier.expectError}.
     *
     * @param txnId       transaction ID from {@link #buildFormPayload}
     * @param referenceId original reference/order ID
     * @param txnAmtPaisa amount in PAISA (must match original)
     * @return Mono of validation response
     */
    public Mono<ConnectIpsValidateResponse> validateTransaction(
            String txnId, String referenceId, long txnAmtPaisa) {

        // wrap ALL synchronous work — validation AND
        // RSA signing — inside Mono.defer so any thrown ConnectIpsException
        // becomes a Mono.error signal instead of escaping the reactive pipeline.
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
                // RSA signing failure — emit as reactive error signal
                return Mono.error(e);
            }

            log.debug("[NepalPay Reactive] ConnectIPS validating | txnId={}",
                    txnId);
            return executeValidateRequest(referenceId, txnAmtPaisa, token);
        });
    }

    /**
     * Validate using a pre-built token — test-friendly, skips RSA signing.
     *
     * @param referenceId your reference ID
     * @param txnAmtPaisa amount in PAISA
     * @param token       pre-built token (use mock value in tests)
     * @return Mono of validation response
     */
    public Mono<ConnectIpsValidateResponse> validateTransactionWithToken(
            String referenceId, long txnAmtPaisa, String token) {

        log.debug("[NepalPay Reactive] ConnectIPS validating with pre-built token" +
                " | refId={}", referenceId);
        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /**
     * Returns the ConnectIPS gateway form action URL.
     *
     * @return UAT or production form action URL
     */
    public String formActionUrl() { return formActionUrl; }

    /**
     * Returns true if operating in UAT (sandbox) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() { return sandbox; }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private Mono<ConnectIpsValidateResponse> executeValidateRequest(
            String referenceId, long txnAmtPaisa, String token) {

        ConnectIpsValidateRequest request = new ConnectIpsValidateRequest(
                merchantId, appId, referenceId, txnAmtPaisa, token);

        String basicAuth = Base64.getEncoder().encodeToString(
                (appId + ":" + appPassword).getBytes(StandardCharsets.UTF_8));

        return webClient.post()
                .uri(VALIDATE_PATH)
                .header("Authorization", "Basic " + basicAuth)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res ->
                        res.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.error("[NepalPay Reactive] ConnectIPS validate" +
                                                    " 4xx | refId={} | body={}",
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

    /**
     * Applies Reactor retry with exponential backoff.
     *
     * <p>Retries on:
     * <ul>
     *   <li>{@link WebClientRequestException} — network/transport failures</li>
     *   <li>{@link ConnectIpsException} with httpStatus=0 or >=500</li>
     * </ul>
     *
     * <p>Never retries 4xx — bad requests won't fix themselves on retry.
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
                                        || (t instanceof ConnectIpsException ce
                                        && (ce.httpStatus() == 0
                                        || ce.httpStatus() >= 500)))
                        .doBeforeRetry(s -> log.warn(
                                "[NepalPay Reactive] ConnectIPS retry attempt {}",
                                s.totalRetries() + 1))
        ).onErrorMap(
                Exceptions::isRetryExhausted,
                Throwable::getCause
        );
    }

    /**
     * Validates payment input before signing.
     * Prevents sending blank IDs or zero/negative amounts to ConnectIPS.
     *
     * @throws ConnectIpsException if any input is invalid
     */
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
                    "txnAmtPaisa must be greater than 0. Got: " + txnAmtPaisa);
        }
    }

    /**
     * Loads and extracts the RSA PrivateKey from a PKCS12 (.pfx) file.
     *
     * <p>Iterates all aliases and selects the first key entry explicitly —
     * the first alias is not guaranteed to be a private-key entry.
     *
     * <p>Fails fast at construction time so misconfiguration is detected
     * immediately rather than silently failing on first payment.
     *
     * @param pfxBytes    raw bytes of the CREDITOR.pfx file
     * @param pfxPassword password protecting the .pfx file
     * @return extracted RSA PrivateKey ready for signing
     * @throws ConnectIpsException if the key cannot be loaded for any reason
     */
    private static PrivateKey loadPrivateKey(byte[] pfxBytes, String pfxPassword) {
        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx certificate bytes are empty. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Contact NCHL at connectips@nchl.com.np.");
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
                if (!keyStore.isKeyEntry(alias)) {
                    continue;
                }
                Object key = keyStore.getKey(alias, pfxPassword.toCharArray());
                if (key instanceof PrivateKey pk) {
                    return pk;
                }
            }

            throw new ConnectIpsException(
                    "ConnectIPS .pfx does not contain a private key entry. " +
                            "Ensure the .pfx file from NCHL is a valid PKCS12 certificate.");

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

    /**
     * Signs a message using the cached RSA private key.
     *
     * <p>The private key was loaded once in the constructor.
     * Only the actual signing operation runs here.
     *
     * @param message the plain-text message to sign
     * @return Base64-encoded RSA-SHA256 signature
     * @throws ConnectIpsException if signing fails
     */
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