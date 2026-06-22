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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
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

    // ── Official ConnectIPS URLs ───────────────────────────────────────────
    private static final String UAT_GATEWAY_URL    =
            "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL   =
            "https://connectips.com/connectipswebgw/loginpage";
    private static final String UAT_VALIDATE_BASE  = "https://uat.connectips.com";
    private static final String PROD_VALIDATE_BASE = "https://connectips.com";
    private static final String VALIDATE_PATH      =
            "/connectipswebws/api/creditor/validatetxn";

    // ── Constants ─────────────────────────────────────────────────────────
    private static final String            CURRENCY     = "NPR";
    private static final String            TOKEN_SUFFIX = "TOKEN=TOKEN";
    private static final DateTimeFormatter DATE_FORMAT  =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Default HTTP timeout for ConnectIPS API calls (connect + read).
     *
     * <p>30 seconds is intentionally longer than Khalti/eSewa's 10s default.
     * ConnectIPS validates bank transfers via NCHL — bank systems can
     * legitimately be slower than commercial payment gateway APIs.
     *
     * <p>TODO: Make configurable via nepalpay.connectips.timeout-seconds
     *          (tracked in issue #7)
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // ── Fields ────────────────────────────────────────────────────────────
    private final int          merchantId;
    private final String       appId;
    private final String       appName;
    private final String       appPassword;
    private final byte[]       pfxBytes;
    private final String       pfxPassword;
    private final boolean      sandbox;
    private final String       formActionUrl;
    private final RestClient   restClient;
    private final RetryProperties retryProps;

    /**
     * RSA private key extracted from the CREDITOR.pfx file.
     *
     * <p>Loaded ONCE in the constructor instead of on every
     * {@code buildFormPayload()} and {@code validateTransaction()} call.
     *
     * <p>KeyStore loading involves password-based key derivation (PBKDF),
     * MAC verification, and ASN.1 parsing — all expensive cryptographic
     * operations. Caching the extracted key eliminates this overhead from
     * every payment operation.
     *
     * <p>{@code private final} (not static) because each ConnectIpsClient
     * instance may use a different .pfx file — static would share one key
     * across all instances, causing silent security bugs in multi-tenant use.
     */
    private final PrivateKey privateKey;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

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

        // Extract private key ONCE at construction time.
        // Fails fast with ConnectIpsException if .pfx is invalid —
        // better to fail at startup than silently fail during payment.
        this.privateKey = loadPrivateKey(pfxBytes, pfxPassword);

        // Apply timeout — 30s for bank transfers (see DEFAULT_TIMEOUT_SECONDS).
        // SimpleClientHttpRequestFactory is built into spring-web —
        // no extra HTTP client dependency needed.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));

        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] ConnectIpsClient initialized | mode={} | merchantId={}" +
                        " | validateUrl={} | timeout={}s | retry={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId,
                validateBaseUrlOverride,
                DEFAULT_TIMEOUT_SECONDS,
                this.retryProps.summary());
    }

    /**
     * Test-only constructor — accepts a pre-built PrivateKey directly.
     *
     * <p>Avoids needing a real .pfx file in tests. Package-private so only
     * test code in the same package can use it.
     *
     * @param privateKey pre-built RSA key (use JVM-generated key in tests)
     */
    ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this.merchantId    = merchantId;
        this.appId         = appId;
        this.appName       = appName;
        this.appPassword   = appPassword;
        this.pfxBytes      = new byte[0]; // not used — key provided directly
        this.pfxPassword   = "";          // not used — key provided directly
        this.privateKey    = privateKey;  // injected directly — no pfx loading
        this.sandbox       = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps    = (retry != null) ? retry : RetryProperties.DEFAULT;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));

        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE STATIC HELPERS — called from constructor
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Load and extract the RSA private key from a PKCS12 (.pfx) file.
     *
     * <p>Called ONCE from the constructor. Any configuration error
     * (wrong password, corrupt file, missing file) causes an immediate
     * {@link ConnectIpsException} at startup rather than at payment time.
     *
     * <p>This is intentional "fail fast" behaviour — a misconfigured
     * .pfx file should prevent the application from starting cleanly,
     * not silently fail on the first real payment.
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
            String alias = keyStore.aliases().nextElement();
            return (PrivateKey) keyStore.getKey(alias, pfxPassword.toCharArray());
        } catch (ConnectIpsException e) {
            throw e; // re-throw validation errors unchanged
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS RSA private key from .pfx file. " +
                            "Ensure pfx-path points to a valid PKCS12 file and " +
                            "pfx-password is correct. Error: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build a signed ConnectIPS form payload from a request object.
     *
     * @param request payment request built via
     *                {@link ConnectIpsPaymentRequest#builder()}
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
     * @param txnAmtPaisa Transaction amount in PAISA (NPR x 100)
     * @param referenceId Your order or reference ID
     * @param remarks     Optional remarks
     * @param particulars Optional particulars
     * @return signed form payload
     * @throws ConnectIpsException if RSA signing fails or config is invalid
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId, long txnAmtPaisa, String referenceId,
            String remarks, String particulars) {

        String txnDate         = LocalDate.now().format(DATE_FORMAT);
        String safeRemarks     = remarks     != null ? remarks     : "";
        String safeParticulars = particulars != null ? particulars : "";

        String message = buildTokenMessage(
                txnId, txnDate, txnAmtPaisa,
                referenceId, safeRemarks, safeParticulars);
        String token = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS form payload built | txnId={} | amtPaisa={}",
                txnId, txnAmtPaisa);

        return new ConnectIpsFormPayload(
                merchantId, appId, appName, txnId, txnDate,
                CURRENCY, txnAmtPaisa, referenceId,
                safeRemarks, safeParticulars,
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

        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String message = buildTokenMessage(
                txnId, txnDate, txnAmtPaisa, referenceId, "", "");
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

        log.debug("[NepalPay] ConnectIPS validating with pre-built token" +
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
                        log.error("[NepalPay] ConnectIPS validate 4xx" +
                                " | refId={} | body={}", referenceId, body);
                        throw new ConnectIpsException(
                                "ConnectIPS validation failed",
                                res.getStatusCode().value(), body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] ConnectIPS validate 5xx" +
                                " | refId={}", referenceId);
                        throw new ConnectIpsException(
                                "ConnectIPS server error during validation",
                                res.getStatusCode().value(), null);
                    })
                    .body(ConnectIpsValidateResponse.class);

            if (response == null) {
                throw new ConnectIpsException(
                        "ConnectIPS returned empty validation response" +
                                " for refId=" + referenceId);
            }

            log.info("[NepalPay] ConnectIPS validate result | refId={}" +
                            " | status={} | success={}",
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

        int  attempt = 0;
        long delayMs = retryProps.initialDelayMs();

        while (true) {
            try {
                return operation.get();

            } catch (ConnectIpsException e) {
                if (e.httpStatus() >= 400 && e.httpStatus() < 500) {
                    throw e;
                }

                attempt++;

                if (attempt > retryProps.maxAttempts()) {
                    log.error("[NepalPay] {} failed after {} attempt(s)" +
                                    " | lastStatus={}",
                            operationName, attempt, e.httpStatus());
                    throw e;
                }

                long waitMs = RetryProperties.jitter(delayMs);
                log.warn("[NepalPay] {} failed (attempt {}/{}) | httpStatus={}" +
                                " | retrying in {}ms",
                        operationName, attempt, retryProps.maxAttempts(),
                        e.httpStatus(), waitMs);

                sleepForRetry(waitMs, e);
                delayMs = retryProps.nextDelay(delayMs);

            } catch (Exception e) {
                throw new ConnectIpsException(
                        "Unexpected error during " + operationName
                                + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Sleeps for the given duration during retry backoff.
     *
     * <p>Extracted from the retry loop to avoid IDE warnings about
     * {@code Thread.sleep()} in a loop body. This is intentional
     * exponential backoff — not busy-waiting.
     *
     * @param waitMs      milliseconds to sleep
     * @param onInterrupt exception to rethrow if thread is interrupted
     */
    private void sleepForRetry(long waitMs, ConnectIpsException onInterrupt) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw onInterrupt;
        }
    }

    private String buildTokenMessage(
            String txnId, String txnDate, long txnAmtPaisa,
            String referenceId, String remarks, String particulars) {

        return "MERCHANTID=" + merchantId
                + ",APPID="        + appId
                + ",APPNAME="      + appName
                + ",TXNID="        + txnId
                + ",TXNDATE="      + txnDate
                + ",TXNCRNCY="     + CURRENCY
                + ",TXNAMT="       + txnAmtPaisa
                + ",REFERENCEID="  + referenceId
                + ",REMARKS="      + remarks
                + ",PARTICULARS="  + particulars
                + ","              + TOKEN_SUFFIX;
    }

    /**
     * Signs a message using the cached RSA private key.
     *
     * <p>The private key was loaded once in the constructor.
     * Only the actual signing operation runs here — no KeyStore
     * loading, no password-based key derivation.
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

    private String readBodySafely(
            org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}