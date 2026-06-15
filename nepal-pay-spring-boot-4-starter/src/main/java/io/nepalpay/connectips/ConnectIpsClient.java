package io.nepalpay.connectips;

import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
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

/**
 * ConnectIPS Payment Gateway Client for Spring Boot 4.1.0.
 *
 * <p>ConnectIPS is operated by Nepal Clearing House Ltd (NCHL),
 * regulated by Nepal Rastra Bank (NRB). It enables direct
 * bank-to-bank payments for merchants registered with NCHL.
 *
 * <p><strong>Merchant Registration Required:</strong>
 * ConnectIPS requires merchant registration with NCHL before use.
 * You will receive:
 * <ul>
 *   <li>A merchant ID</li>
 *   <li>An application ID and name</li>
 *   <li>A CREDITOR.pfx certificate file (for RSA signing)</li>
 *   <li>A password for the .pfx file</li>
 * </ul>
 * Contact your bank or NCHL at connectips@nchl.com.np to register.
 *
 * <p>This client provides:
 * <ul>
 *   <li>{@link #buildFormPayload(ConnectIpsPaymentRequest)} — Build RSA-signed form payload</li>
 *   <li>{@link #validateTransaction(String, String, long)} — Verify transaction server-side</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsClient {

    // ── Official ConnectIPS gateway URLs ──────────────────────────────────────
    private static final String UAT_GATEWAY_URL =
            "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL =
            "https://connectips.com/connectipswebgw/loginpage";

    // ── Official ConnectIPS validation API base URLs ──────────────────────────
    private static final String UAT_VALIDATE_BASE =
            "https://uat.connectips.com";
    private static final String PROD_VALIDATE_BASE =
            "https://connectips.com";

    private static final String VALIDATE_PATH =
            "/connectipswebws/api/creditor/validatetxn";

    private static final String CURRENCY    = "NPR";
    private static final String TOKEN_SUFFIX = "TOKEN=TOKEN";

    //  yyyy (calendar year) not YYYY (week year)
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final int    merchantId;
    private final String appId;
    private final String appName;
    private final String appPassword;
    private final byte[] pfxBytes;
    private final String pfxPassword;
    private final boolean sandbox;
    private final String formActionUrl;
    private final RestClient restClient;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     *
     * <p>Validate API base URL is automatically determined from
     * {@code nepalpay.connectips.sandbox}:
     * <ul>
     *   <li>sandbox=true  → https://uat.connectips.com</li>
     *   <li>sandbox=false → https://connectips.com</li>
     * </ul>
     *
     * @param merchantId  NCHL-assigned merchant ID
     * @param appId       Application ID from NCHL
     * @param appName     Application name from NCHL
     * @param appPassword Application password (for Basic Auth on validate API)
     * @param pfxPath    Contents of Cfile:/app/CREDITOR.pfx as String path
     * @param pfxPassword Password for the .pfx file
     * @param sandbox     true = UAT, false = production
     * @param builder     Spring Boot RestClient builder
     */
    public ConnectIpsClient(
            int merchantId,
            String appId,
            String appName,
            String appPassword,
            byte[] pfxPath,
            String pfxPassword,
            boolean sandbox,
            RestClient.Builder builder) {

        this(
                merchantId, appId, appName, appPassword,
                pfxPath, pfxPassword, sandbox, builder,
                sandbox ? UAT_VALIDATE_BASE : PROD_VALIDATE_BASE
        );
    }

    /**
     * Test constructor — allows injecting a custom validate API base URL.
     *
     * <p>Used in tests to point the validate API calls at a
     * {@code MockWebServer} instead of the real ConnectIPS API.
     *
     * <p>Example test usage:
     * <pre>{@code
     * ConnectIpsClient client = new ConnectIpsClient(
     *     123, "APP-001", "TestApp", "password",
     *     new byte[]{1}, "pfxpass", true,
     *     RestClient.builder(),
     *     mockWebServer.url("/").toString()
     * );
     * }</pre>
     *
     * @param merchantId             NCHL-assigned merchant ID
     * @param appId                  Application ID
     * @param appName                Application name
     * @param appPassword            Application password
     * @param pfxBytes               CREDITOR.pfx file bytes
     * @param pfxPassword            Password for .pfx file
     * @param sandbox                true = UAT, false = production
     * @param builder                RestClient builder
     * @param validateBaseUrlOverride Custom base URL for validate API
     */
    public ConnectIpsClient(
            int merchantId,
            String appId,
            String appName,
            String appPassword,
            byte[] pfxBytes,
            String pfxPassword,
            boolean sandbox,
            RestClient.Builder builder,
            String validateBaseUrlOverride) {

        this.merchantId  = merchantId;
        this.appId       = appId;
        this.appName     = appName;
        this.appPassword = appPassword;
        this.pfxBytes    = pfxBytes;
        this.pfxPassword = pfxPassword;
        this.sandbox     = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;

        // ConnectIPS validate API uses HTTP Basic Auth (appId:appPassword)
        this.restClient = builder
                .baseUrl(validateBaseUrlOverride)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] ConnectIpsClient initialized | mode={} | merchantId={} | validateUrl={}",
                sandbox ? "UAT" : "PRODUCTION",
                merchantId,
                validateBaseUrlOverride);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed ConnectIPS form payload from a request object.
     *
     * <p>Convenience overload that accepts a {@link ConnectIpsPaymentRequest}.
     *
     * @param request payment request built via {@link ConnectIpsPaymentRequest#builder()}
     * @return signed form payload to return to the frontend
     * @throws ConnectIpsException if RSA signing fails or config is invalid
     */
    public ConnectIpsFormPayload buildFormPayload(ConnectIpsPaymentRequest request) {
        return buildFormPayload(
                request.txnId(),
                request.txnAmtPaisa(),
                request.referenceId(),
                request.remarks(),
                request.particulars()
        );
    }

    /**
     * Build a signed ConnectIPS form payload.
     *
     * <p>The RSA-SHA256 token is generated using your CREDITOR.pfx certificate.
     * Your frontend POSTs all fields from this payload to {@link #formActionUrl()}.
     *
     * <p>Token message format (field order is MANDATORY per NCHL docs):
     * {@code MERCHANTID=X,APPID=X,APPNAME=X,TXNID=X,TXNDATE=X,
     * TXNCRNCY=NPR,TXNAMT=X,REFERENCEID=X,REMARKS=X,PARTICULARS=X,TOKEN=TOKEN}
     *
     * @param txnId       Unique transaction ID (alphanumeric and hyphens only)
     * @param txnAmtPaisa Transaction amount in PAISA (NPR x 100)
     * @param referenceId Your order or reference ID
     * @param remarks     Optional remarks (empty string if none)
     * @param particulars Optional particulars (empty string if none)
     * @return signed form payload to return to the frontend
     * @throws ConnectIpsException if RSA signing fails or config is invalid
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId,
            long txnAmtPaisa,
            String referenceId,
            String remarks,
            String particulars) {

        validatePfxConfig();

        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String safeRemarks      = (remarks      != null) ? remarks      : "";
        String safeParticulars  = (particulars  != null) ? particulars  : "";

        // Build canonical message — field order is MANDATORY per NCHL docs
        String message = buildTokenMessage(
                txnId, txnDate, txnAmtPaisa,
                referenceId, safeRemarks, safeParticulars
        );

        // Sign with RSA-SHA256 using CREDITOR.pfx private key
        String token = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS form payload built | txnId={} | amtPaisa={}",
                txnId, txnAmtPaisa);

        return new ConnectIpsFormPayload(
                merchantId,
                appId,
                appName,
                txnId,
                txnDate,
                CURRENCY,
                txnAmtPaisa,
                referenceId,
                safeRemarks,
                safeParticulars,
                token,
                formActionUrl
        );
    }

    /**
     * Validate a ConnectIPS transaction after the user completes payment.
     *
     * <p>Always call this after receiving the callback redirect.
     * The redirect URL alone can be faked — always verify server-side.
     *
     * <p>This method:
     * <ol>
     *   <li>Generates an RSA-SHA256 token for the validation request</li>
     *   <li>Calls the ConnectIPS validate API with HTTP Basic Auth</li>
     *   <li>Returns the typed response</li>
     * </ol>
     *
     * @param txnId       The transaction ID from your original buildFormPayload call
     * @param referenceId Your original reference/order ID
     * @param txnAmtPaisa Amount in PAISA (must match original)
     * @return Validation response with status
     * @throws ConnectIpsException if validation fails or API returns an error
     */
    public ConnectIpsValidateResponse validateTransaction(
            String txnId,
            String referenceId,
            long txnAmtPaisa) {

        validatePfxConfig();

        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String message = buildTokenMessage(txnId, txnDate, txnAmtPaisa, referenceId, "", "");
        String token   = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS validating transaction | txnId={}", txnId);

        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /**
     * Validate a ConnectIPS transaction using a pre-built token.
     *
     * <p>This method skips RSA token generation and uses the supplied token directly.
     * It is intended for testing — allows calling the validate API via
     * MockWebServer without a real .pfx certificate.
     *
     * @param referenceId  Your original reference ID
     * @param txnAmtPaisa  Amount in PAISA
     * @param token        Pre-built token string (use a mock value in tests)
     * @return Validation response
     * @throws ConnectIpsException if API call fails
     */
    public ConnectIpsValidateResponse validateTransactionWithToken(
            String referenceId,
            long txnAmtPaisa,
            String token) {

        log.debug("[NepalPay] ConnectIPS validating with pre-built token | refId={}",
                referenceId);

        return executeValidateRequest(referenceId, txnAmtPaisa, token);
    }

    /**
     * Returns the ConnectIPS gateway form action URL.
     *
     * @return UAT or production gateway URL
     */
    public String formActionUrl() {
        return formActionUrl;
    }

    /**
     * Returns true if operating in UAT (sandbox) mode.
     *
     * @return true if UAT mode is active
     */
    public boolean isSandbox() {
        return sandbox;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the HTTP POST to the ConnectIPS validate API.
     * Shared by {@link #validateTransaction} and {@link #validateTransactionWithToken}.
     */
    private ConnectIpsValidateResponse executeValidateRequest(
            String referenceId,
            long txnAmtPaisa,
            String token) {

        ConnectIpsValidateRequest request = new ConnectIpsValidateRequest(
                merchantId, appId, referenceId, txnAmtPaisa, token
        );

        // HTTP Basic Auth: appId:appPassword — Base64 encoded
        String credentials = appId + ":" + appPassword;
        String basicAuth   = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        try {
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
                                res.getStatusCode().value(),
                                body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] ConnectIPS server error during validate | refId={}",
                                referenceId);
                        throw new ConnectIpsException(
                                "ConnectIPS server error during validation",
                                res.getStatusCode().value(),
                                null);
                    })
                    .body(ConnectIpsValidateResponse.class);

            if (response == null) {
                throw new ConnectIpsException(
                        "ConnectIPS returned empty validation response for refId=" + referenceId);
            }

            log.info("[NepalPay] ConnectIPS validate result | refId={} | status={} | success={}",
                    referenceId, response.status(), response.isPaymentSuccessful());

            return response;

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during ConnectIPS validation | refId={}",
                    referenceId, e);
            throw new ConnectIpsException(
                    "Unexpected error during ConnectIPS validation: " + e.getMessage(), e);
        }
    }

    /**
     * Build the canonical token message string.
     *
     * <p>Official NCHL format — field order is MANDATORY:
     * {@code MERCHANTID=X,APPID=X,APPNAME=X,TXNID=X,TXNDATE=X,
     * TXNCRNCY=NPR,TXNAMT=X,REFERENCEID=X,REMARKS=X,PARTICULARS=X,TOKEN=TOKEN}
     */
    private String buildTokenMessage(
            String txnId,
            String txnDate,
            long txnAmtPaisa,
            String referenceId,
            String remarks,
            String particulars) {

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

    /**
     * Generate RSA-SHA256 digital signature using the CREDITOR.pfx private key.
     *
     * <p>ConnectIPS uses SHA256withRSA algorithm.
     * The signed bytes are Base64-encoded as the TOKEN field.
     */
    private String generateRsaToken(String message) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(
                    new ByteArrayInputStream(pfxBytes),
                    pfxPassword.toCharArray()
            );

            String alias = keyStore.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(
                    alias,
                    pfxPassword.toCharArray()
            );

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(sig.sign());

        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to generate ConnectIPS RSA token. " +
                            "Ensure your .pfx file and password are correct. " +
                            "Error: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that the .pfx configuration is present before use.
     *
     * @throws ConnectIpsException if .pfx bytes or password are missing
     */
    private void validatePfxConfig() {
        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx certificate bytes are empty. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Contact NCHL at connectips@nchl.com.np to obtain your certificate.");
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