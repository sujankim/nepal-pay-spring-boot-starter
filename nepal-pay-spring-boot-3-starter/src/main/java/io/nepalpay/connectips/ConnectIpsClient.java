package io.nepalpay.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
import io.nepalpay.core.retry.RetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * ConnectIPS Payment Gateway Client — Spring Boot 3.
 *
 * <p>ConnectIPS is operated by Nepal Clearing House Ltd (NCHL).
 * Requires merchant registration — contact connectips@nchl.com.np.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #buildFormPayload} — RSA-signed form payload</li>
 *   <li>{@link #validateTransaction} — Server-side payment verification</li>
 *   <li>{@link #validateTransactionWithToken} — Test-friendly verification</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsClient {

    // ── Official ConnectIPS URLs ───────────────────────────────────────────────
    private static final String UAT_GATEWAY_URL  = "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL = "https://connectips.com/connectipswebgw/loginpage";
    private static final String UAT_VALIDATE_BASE  = "https://uat.connectips.com";
    private static final String PROD_VALIDATE_BASE = "https://connectips.com";
    private static final String VALIDATE_PATH = "/connectipswebws/api/creditor/validatetxn";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final String CURRENCY     = "NPR";
    private static final String TOKEN_SUFFIX = "TOKEN=TOKEN";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // ── Fields ────────────────────────────────────────────────────────────────
    private final int    merchantId;
    private final String appId;
    private final String appName;
    private final String appPassword;
    private final byte[] pfxBytes;
    private final String pfxPassword;
    private final boolean sandbox;
    private final String formActionUrl;
    private final RestClient restClient;
    private final RetryProperties retryProps;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor (8-arg) — used by auto-configuration without retry.
     * Delegates to 9-arg constructor with DEFAULT retry.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE);
    }

    /**
     * Test constructor (9-arg) — custom validate API URL, DEFAULT retry.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder, validateBaseUrlOverride, RetryProperties.DEFAULT);
    }

    /**
     * Production constructor with explicit retry (9-arg + retry).
     * Used by auto-configuration when retry is configured.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, RetryProperties retry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry);
    }

    /**
     * Full constructor (10-arg) — custom validate URL + explicit retry.
     * Used in retry tests.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this.merchantId    = merchantId;
        this.appId         = appId;
        this.appName       = appName;
        this.appPassword   = appPassword;
        this.pfxBytes      = pfxBytes;
        this.pfxPassword   = pfxPassword;
        this.sandbox       = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps    = (retry != null) ? retry : RetryProperties.DEFAULT;

        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] ConnectIpsClient initialized | mode={} | merchantId={}" +
                        " | validateUrl={} | retry={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId,
                validateBaseUrlOverride,
                this.retryProps.summary());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed ConnectIPS form payload from a request object.
     *
     * @param request payment request built via {@link ConnectIpsPaymentRequest#builder()}
     * @return signed form payload
     * @throws ConnectIpsException if RSA signing fails
     */
    public ConnectIpsFormPayload buildFormPayload(ConnectIpsPaymentRequest request) {
        return buildFormPayload(request.txnId(), request.txnAmtPaisa(),
                request.referenceId(), request.remarks(), request.particulars());
    }

    /**
     * Build a signed ConnectIPS form payload.
     *
     * @param txnId       Unique transaction ID
     * @param txnAmtPaisa Transaction amount in PAISA (NPR × 100)
     * @param referenceId Your order or reference ID
     * @param remarks     Optional remarks
     * @param particulars Optional particulars
     * @return signed form payload
     * @throws ConnectIpsException if RSA signing fails or config is invalid
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId, long txnAmtPaisa, String referenceId,
            String remarks, String particulars) {

        validatePfxConfig();

        String txnDate        = LocalDate.now().format(DATE_FORMAT);
        String safeRemarks    = remarks    != null ? remarks    : "";
        String safeParticulars = particulars != null ? particulars : "";

        String message = buildTokenMessage(txnId, txnDate, txnAmtPaisa,
                referenceId, safeRemarks, safeParticulars);
        String token = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS form payload built | txnId={} | amtPaisa={}",
                txnId, txnAmtPaisa);

        return new ConnectIpsFormPayload(merchantId, appId, appName, txnId, txnDate,
                CURRENCY, txnAmtPaisa, referenceId, safeRemarks, safeParticulars,
                token, formActionUrl);
    }

    /**
     * Validate a ConnectIPS transaction after payment.
     *
     * @param txnId       Transaction ID from {@link #buildFormPayload}
     * @param referenceId Original reference/order ID
     * @param txnAmtPaisa Amount in PAISA (must match original)
     * @return Validation response
     * @throws ConnectIpsException if validation fails
     */
    public ConnectIpsValidateResponse validateTransaction(
            String txnId, String referenceId, long txnAmtPaisa) {

        validatePfxConfig();

        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String message = buildTokenMessage(txnId, txnDate, txnAmtPaisa, referenceId, "", "");
        String token   = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS validating | txnId={}", txnId);
        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /**
     * Validate using a pre-built token — test-friendly, skips RSA signing.
     *
     * @param referenceId Your reference ID
     * @param txnAmtPaisa Amount in PAISA
     * @param token       Pre-built token (use mock value in tests)
     * @return Validation response
     */
    public ConnectIpsValidateResponse validateTransactionWithToken(
            String referenceId, long txnAmtPaisa, String token) {

        log.debug("[NepalPay] ConnectIPS validating with pre-built token | refId={}", referenceId);
        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /** @return ConnectIPS gateway form action URL */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if operating in UAT (sandbox) mode */
    public boolean isSandbox() { return sandbox; }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private ConnectIpsValidateResponse executeValidateRequest(
            String referenceId, long txnAmtPaisa, String token) {

        ConnectIpsValidateRequest request = new ConnectIpsValidateRequest(
                merchantId, appId, referenceId, txnAmtPaisa, token);

        String credentials = appId + ":" + appPassword;
        String basicAuth   = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        return executeWithRetry("ConnectIPS validate", () -> {
            ConnectIpsValidateResponse response = restClient.post()
                    .uri(VALIDATE_PATH)
                    .header("Authorization", "Basic " + basicAuth)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error("[NepalPay] ConnectIPS validate 4xx | refId={} | body={}",
                                referenceId, body);
                        throw new ConnectIpsException(
                                "ConnectIPS validation failed",
                                res.getStatusCode().value(), body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] ConnectIPS validate 5xx | refId={}", referenceId);
                        throw new ConnectIpsException(
                                "ConnectIPS server error during validation",
                                res.getStatusCode().value(), null);
                    })
                    .body(ConnectIpsValidateResponse.class);

            if (response == null) {
                throw new ConnectIpsException(
                        "ConnectIPS returned empty validation response for refId=" + referenceId);
            }

            log.info("[NepalPay] ConnectIPS validate result | refId={} | status={} | success={}",
                    referenceId, response.status(), response.isPaymentSuccessful());
            return response;
        });
    }

    /**
     * Executes a ConnectIPS API call with exponential backoff retry.
     * Never retries 4xx client errors.
     */
    private <T> T executeWithRetry(String operationName, Supplier<T> operation) {
        if (!retryProps.isActive()) {
            return operation.get();
        }

        int attempt                    = 0;
        long delayMs                   = retryProps.initialDelayMs();
        ConnectIpsException lastException = null;

        while (true) {
            try {
                return operation.get();

            } catch (ConnectIpsException e) {
                if (e.httpStatus() >= 400 && e.httpStatus() < 500) {
                    throw e;
                }

                attempt++;
                lastException = e;

                if (attempt > retryProps.maxAttempts()) {
                    log.error("[NepalPay] {} failed after {} attempt(s) | lastStatus={}",
                            operationName, attempt, e.httpStatus());
                    throw e;
                }

                long waitMs = RetryProperties.jitter(delayMs);
                log.warn("[NepalPay] {} failed (attempt {}/{}) | httpStatus={} | retrying in {}ms",
                        operationName, attempt, retryProps.maxAttempts(), e.httpStatus(), waitMs);

                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw lastException;
                }

                delayMs = retryProps.nextDelay(delayMs);

            } catch (Exception e) {
                throw new ConnectIpsException(
                        "Unexpected error during " + operationName + ": " + e.getMessage(), e);
            }
        }
    }

    private String buildTokenMessage(
            String txnId, String txnDate, long txnAmtPaisa,
            String referenceId, String remarks, String particulars) {

        return "MERCHANTID=" + merchantId
                + ",APPID=" + appId
                + ",APPNAME=" + appName
                + ",TXNID=" + txnId
                + ",TXNDATE=" + txnDate
                + ",TXNCRNCY=" + CURRENCY
                + ",TXNAMT=" + txnAmtPaisa
                + ",REFERENCEID=" + referenceId
                + ",REMARKS=" + remarks
                + ",PARTICULARS=" + particulars
                + "," + TOKEN_SUFFIX;
    }

    private String generateRsaToken(String message) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(pfxBytes), pfxPassword.toCharArray());
            String alias = keyStore.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, pfxPassword.toCharArray());
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to generate ConnectIPS RSA token. " +
                            "Ensure your .pfx file and password are correct. Error: " + e.getMessage(), e);
        }
    }

    private void validatePfxConfig() {
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
    }

    private String readBodySafely(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}