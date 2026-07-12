package io.nepalpay.connectips;

import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Enumeration;
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
 * <p><strong>Metrics:</strong>
 * If this client is constructed with a
 * {@link MeterRegistry}, {@code validateTransaction()} is timed
 * and retry attempts are counted via {@link ConnectIpsMetrics}.
 * When no {@link MeterRegistry} is provided, all metric recording is
 * silently skipped — zero impact on existing users without Actuator.
 * Metrics auto-configuration is active — wired via NepalPayMetricsAutoConfiguration.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsClient {

    // ── Official ConnectIPS URLs ──────────────────────────────────────────
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
     * This constant is used as the fallback when no properties are injected
     * (e.g. in the test-only PrivateKey constructor).
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // ── Fields ────────────────────────────────────────────────────────────
    private final int             merchantId;
    private final String          appId;
    private final String          appName;
    private final String          appPassword;
    private final byte[]          pfxBytes;
    private final String          pfxPassword;
    private final boolean         sandbox;
    private final String          formActionUrl;
    private final RestClient      restClient;
    private final RetryProperties retryProps;
    private final PrivateKey      privateKey;
    private final int             timeoutSeconds;

    /**
     * Optional Micrometer metrics — null when Actuator not on classpath.
     * All usages guarded with null check — no NPE possible.
     */
    private final ConnectIpsMetrics metrics;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Production constructor (8-arg) — no metrics, DEFAULT retry,
     * default timeout.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                RetryProperties.DEFAULT, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Test constructor (9-arg) — custom validate URL, DEFAULT retry,
     * no metrics, default timeout.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder, validateBaseUrlOverride,
                RetryProperties.DEFAULT, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Production constructor with explicit retry — no metrics, default timeout.
     * Used by auto-configuration when retry is configured but Actuator absent.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, RetryProperties retry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Full constructor — custom validate URL + explicit retry.
     * No metrics, default timeout. Used in retry tests.
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder, validateBaseUrlOverride,
                retry, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Constructor with explicit retry + optional metrics + default timeout.
     * Used by auto-configuration when Actuator is present.
     *
     * @param meterRegistry Micrometer registry — null means no metrics
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, RetryProperties retry,
            MeterRegistry meterRegistry) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
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
     * <p>Used by auto-configuration when
     * {@code nepalpay.connectips.timeout-seconds} is set.
     *
     * @param retry          retry configuration
     * @param meterRegistry  Micrometer registry — null means no metrics
     * @param timeoutSeconds HTTP connect/read timeout in seconds
     */
    public ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, RetryProperties retry,
            MeterRegistry meterRegistry, int timeoutSeconds) {

        this(merchantId, appId, appName, appPassword, pfxBytes, pfxPassword,
                sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE,
                retry, meterRegistry, timeoutSeconds);
    }

    /**
     * Core private constructor — single point of initialization.
     * All other constructors delegate here.
     */
    private ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            byte[] pfxBytes, String pfxPassword, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry, MeterRegistry meterRegistry,
            int timeoutSeconds) {

        this.merchantId     = merchantId;
        this.appId          = appId;
        this.appName        = appName;
        this.appPassword    = appPassword;
        this.pfxBytes       = pfxBytes;
        this.pfxPassword    = pfxPassword;
        this.sandbox        = sandbox;
        this.formActionUrl  = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps     = (retry != null) ? retry : RetryProperties.DEFAULT;
        this.privateKey     = loadPrivateKey(pfxBytes, pfxPassword);
        this.timeoutSeconds = timeoutSeconds;

        // ── Metrics — optional ────────────────────────────────────────────
        this.metrics = (meterRegistry != null)
                ? new ConnectIpsMetrics(meterRegistry, sandbox)
                : null;

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(this.timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(this.timeoutSeconds));

        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] ConnectIpsClient initialized | mode={} | merchantId={}" +
                        " | validateUrl={} | timeout={}s | retry={} | metrics={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId, validateBaseUrlOverride,
                this.timeoutSeconds,
                this.retryProps.summary(),
                metrics != null ? "enabled" : "disabled");
    }

    /**
     * Test-only constructor — accepts a pre-built PrivateKey directly.
     * Package-private so only test code in the same package can use it.
     */
    ConnectIpsClient(
            int merchantId, String appId, String appName, String appPassword,
            PrivateKey privateKey, boolean sandbox,
            RestClient.Builder builder, String validateBaseUrlOverride,
            RetryProperties retry) {

        this.merchantId     = merchantId;
        this.appId          = appId;
        this.appName        = appName;
        this.appPassword    = appPassword;
        this.pfxBytes       = new byte[0];
        this.pfxPassword    = "";
        this.privateKey     = privateKey;
        this.sandbox        = sandbox;
        this.formActionUrl  = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;
        this.retryProps     = (retry != null) ? retry : RetryProperties.DEFAULT;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        this.metrics        = null;

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(this.timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(this.timeoutSeconds));

        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE STATIC HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Load and extract RSA PrivateKey from a PKCS12 (.pfx) file.
     *
     * <p><strong>Fix (D-34):</strong> Iterates all KeyStore aliases and
     * checks {@code isKeyEntry()} before selecting — matches the fix already
     * applied to {@code ConnectIpsReactiveClient}.
     */
    private static PrivateKey loadPrivateKey(
            byte[] pfxBytes, String pfxPassword) {

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
                if (!keyStore.isKeyEntry(alias)) continue;
                Object key = keyStore.getKey(alias, pfxPassword.toCharArray());
                if (key instanceof PrivateKey pk) return pk;
            }

            throw new ConnectIpsException(
                    "ConnectIPS .pfx does not contain a private key entry. " +
                            "Ensure the .pfx file was issued by NCHL and is valid.");

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS RSA private key from .pfx. " +
                            "Ensure pfx-path is a valid PKCS12 file and " +
                            "pfx-password is correct. Error: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build a signed ConnectIPS form payload from a request object.
     *
     * @param request payment request
     * @return signed form payload
     * @throws ConnectIpsException if RSA signing fails
     */
    public ConnectIpsFormPayload buildFormPayload(
            ConnectIpsPaymentRequest request) {
        return buildFormPayload(
                request.txnId(), request.txnAmtPaisa(),
                request.referenceId(), request.remarks(),
                request.particulars());
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

        log.debug("[NepalPay] ConnectIPS form payload built" +
                " | txnId={} | amtPaisa={}", txnId, txnAmtPaisa);

        return new ConnectIpsFormPayload(
                merchantId, appId, appName, txnId, txnDate,
                CURRENCY, txnAmtPaisa, referenceId,
                safeRemarks, safeParticulars, token, formActionUrl);
    }

    /**
     * Validate a ConnectIPS transaction after payment.
     *
     * <p>Records {@code nepalpay.connectips.validate.duration} timer
     * when Micrometer is available.
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

        if (metrics != null) {
            return metrics.recordValidate(
                    () -> executeValidateRequest(referenceId, txnAmtPaisa, token));
        }
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

    /** @return UAT or production form action URL */
    public String formActionUrl() { return formActionUrl; }

    /** @return true if sandbox/UAT mode is active */
    public boolean isSandbox() { return sandbox; }

    /** @return configured HTTP timeout in seconds */
    public int timeoutSeconds() { return timeoutSeconds; }

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

        return executeWithRetry(
                "ConnectIPS validate",
                metrics != null ? metrics::incrementValidateRetry : null,
                () -> {
                    ConnectIpsValidateResponse response = restClient.post()
                            .uri(VALIDATE_PATH)
                            .header("Authorization", "Basic " + basicAuth)
                            .body(request)
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError,
                                    (req, res) -> {
                                        String body = readBodySafely(res);
                                        log.error("[NepalPay] ConnectIPS validate" +
                                                        " 4xx | refId={} | body={}",
                                                referenceId, body);
                                        throw new ConnectIpsException(
                                                "ConnectIPS validation failed",
                                                res.getStatusCode().value(), body);
                                    })
                            .onStatus(HttpStatusCode::is5xxServerError,
                                    (req, res) -> {
                                        log.error("[NepalPay] ConnectIPS validate" +
                                                " 5xx | refId={}", referenceId);
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
                            referenceId, response.status(),
                            response.isPaymentSuccessful());
                    return response;
                });
    }

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
                throw new ConnectIpsException(
                        "Unexpected error during " + operationName
                                + ": " + e.getMessage(), e);
            }
        }
    }

    private void sleepForRetry(
            long waitMs, ConnectIpsException onInterrupt) {
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

    private String readBodySafely(
            org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}