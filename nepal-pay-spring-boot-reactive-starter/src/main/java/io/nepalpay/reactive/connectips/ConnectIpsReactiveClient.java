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

    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE);
    }

    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, String validateBaseUrlOverride) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder, validateBaseUrlOverride, RetryProperties.DEFAULT);
    }

    public ConnectIpsReactiveClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            WebClient.Builder builder, RetryProperties retry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE, retry);
    }

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

    /** Test-only constructor — accepts pre-built PrivateKey. */
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
     * Build signed ConnectIPS form payload — synchronous.
     *
     * <p>Validates inputs before signing to avoid sending invalid data.
     */
    public ConnectIpsFormPayload buildFormPayload(
            ConnectIpsPaymentRequest request) {

        // ✅ CodeRabbit fix: null check before accessing fields
        if (request == null) {
            throw new ConnectIpsException(
                    "ConnectIpsPaymentRequest cannot be null");
        }

        return buildFormPayload(
                request.txnId(), request.txnAmtPaisa(),
                request.referenceId(), request.remarks(), request.particulars());
    }

    /**
     * Build signed ConnectIPS form payload — synchronous.
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId, long txnAmtPaisa, String referenceId,
            String remarks, String particulars) {

        // validate inputs before expensive RSA signing
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
     * Validate a ConnectIPS transaction — reactive.
     */
    public Mono<ConnectIpsValidateResponse> validateTransaction(
            String txnId, String referenceId, long txnAmtPaisa) {

        // validate before signing
        try {
            validatePaymentInput(txnId, referenceId, txnAmtPaisa);
        } catch (ConnectIpsException e) {
            return Mono.error(e);
        }

        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String message = buildTokenMessage(
                txnId, txnDate, txnAmtPaisa, referenceId, "", "");
        String token   = generateRsaToken(message);

        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /**
     * Validate using pre-built token — test-friendly.
     */
    public Mono<ConnectIpsValidateResponse> validateTransactionWithToken(
            String referenceId, long txnAmtPaisa, String token) {

        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /** @return form action URL */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if sandbox mode */
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
                                .flatMap(body -> Mono.error(
                                        new ConnectIpsException(
                                                "ConnectIPS validation failed",
                                                res.statusCode().value(), body))))
                .onStatus(HttpStatusCode::is5xxServerError, res ->
                        Mono.error(new ConnectIpsException(
                                "ConnectIPS server error during validation",
                                res.statusCode().value(), null)))
                .bodyToMono(ConnectIpsValidateResponse.class)
                .doOnNext(res -> log.info(
                        "[NepalPay Reactive] ConnectIPS validate result" +
                                " | refId={} | success={}",
                        referenceId, res.isPaymentSuccessful()))
                .transform(this::withRetry);
    }

    private <T> Mono<T> withRetry(Mono<T> source) {
        if (!retryProps.isActive()) return source;
        return source.retryWhen(
                Retry.backoff(
                                retryProps.maxAttempts(),
                                Duration.ofMillis(retryProps.initialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryProps.maxDelayMs()))
                        .jitter(0.1)
                        .filter(t ->
                                // retry transport failures too
                                t instanceof WebClientRequestException
                                        || (t instanceof ConnectIpsException ce
                                        && (ce.httpStatus() == 0
                                        || ce.httpStatus() >= 500)))
                        .doBeforeRetry(s -> log.warn(
                                "[NepalPay Reactive] ConnectIPS retry attempt {}",
                                s.totalRetries() + 1))
        ).onErrorMap(
                // correct API — reactor.core.Exceptions.isRetryExhausted
                Exceptions::isRetryExhausted,
                Throwable::getCause
        );
    }

    /**
     * Validates payment input before signing.
     * Prevents sending blank IDs or zero/negative amounts to ConnectIPS.
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
     * Loads and returns the RSA PrivateKey from a PKCS12 .pfx file.
     *
     * <p>Iterates all aliases and selects the first key entry explicitly —
     * the first alias is not guaranteed to be a private-key entry.
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

            // iterate aliases and select the first private-key
            // entry explicitly. The first alias is not guaranteed to be a key entry.
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.isKeyEntry(alias)) {
                    continue;
                }
                Object key = keyStore.getKey(alias, pfxPassword.toCharArray());
                if (key instanceof PrivateKey privateKey) {
                    return privateKey;
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