package io.nepalpay.connectips;

import io.nepalpay.connectips.exception.ConnectIpsException;
import io.nepalpay.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.connectips.model.ConnectIpsPaymentStatus;
import io.nepalpay.connectips.model.ConnectIpsValidateRequest;
import io.nepalpay.connectips.model.ConnectIpsValidateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

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
 * <p>IMPORTANT — Merchant Registration Required:
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
 *   <li>{@link #buildFormPayload} — Build RSA-signed form payload</li>
 *   <li>{@link #validateTransaction} — Verify transaction server-side</li>
 * </ul>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class ConnectIpsClient {

    // Official ConnectIPS gateway URLs
    private static final String UAT_GATEWAY_URL =
            "https://uat.connectips.com/connectipswebgw/loginpage";
    private static final String PROD_GATEWAY_URL =
            "https://connectips.com/connectipswebgw/loginpage";

    // Official ConnectIPS validation API URLs
    private static final String UAT_VALIDATE_URL =
            "https://uat.connectips.com";
    private static final String PROD_VALIDATE_URL =
            "https://connectips.com";
    private static final String VALIDATE_PATH =
            "/connectipswebws/api/creditor/validatetxn";

    private static final String CURRENCY = "NPR";
    private static final String TOKEN_SUFFIX = "TOKEN=TOKEN";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-YYYY");

    private final int merchantId;
    private final String appId;
    private final String appName;
    private final String appPassword;
    private final byte[] pfxBytes;
    private final String pfxPassword;
    private final boolean sandbox;
    private final RestClient restClient;
    private final String formActionUrl;

    /**
     * Production constructor — used by auto-configuration.
     *
     * <p>All parameters come from {@code nepalpay.connectips.*} properties.
     *
     * @param merchantId   NCHL-assigned merchant ID
     * @param appId        Application ID from NCHL
     * @param appName      Application name from NCHL
     * @param appPassword  Application password (for Basic Auth on validate API)
     * @param pfxBytes     Contents of the CREDITOR.pfx file as bytes
     * @param pfxPassword  Password for the .pfx file
     * @param sandbox      true = UAT, false = production
     * @param builder      Spring Boot RestClient builder
     */
    public ConnectIpsClient(
            int merchantId,
            String appId,
            String appName,
            String appPassword,
            byte[] pfxBytes,
            String pfxPassword,
            boolean sandbox,
            RestClient.Builder builder) {

        this.merchantId  = merchantId;
        this.appId       = appId;
        this.appName     = appName;
        this.appPassword = appPassword;
        this.pfxBytes    = pfxBytes;
        this.pfxPassword = pfxPassword;
        this.sandbox     = sandbox;
        this.formActionUrl = sandbox ? UAT_GATEWAY_URL : PROD_GATEWAY_URL;

        String validateBase = sandbox ? UAT_VALIDATE_URL : PROD_VALIDATE_URL;

        // ConnectIPS validate API uses HTTP Basic Auth (appId:appPassword)
        this.restClient = builder
                .baseUrl(validateBase)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] ConnectIpsClient initialized | mode={} | merchantId={}",
                sandbox ? "UAT" : "PRODUCTION", merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed ConnectIPS form payload.
     *
     * <p>The RSA-SHA256 token is generated using your CREDITOR.pfx certificate.
     * The frontend POSTs all payload fields to {@code formActionUrl}.
     *
     * <p>Message format for token generation (field order is MANDATORY):
     * {@code MERCHANTID=X,APPID=X,APPNAME=X,TXNID=X,TXNDATE=X,
     *          TXNCRNCY=X,TXNAMT=X,REFERENCEID=X,REMARKS=X,PARTICULARS=X,TOKEN=TOKEN}
     *
     * @param txnId       Your unique transaction ID (alphanumeric, no spaces)
     * @param txnAmt      Transaction amount in paisa
     * @param referenceId Your order/reference ID
     * @param remarks     Optional remarks (pass empty string if none)
     * @param particulars Optional particulars (pass empty string if none)
     * @return Signed form payload to return to the frontend
     * @throws ConnectIpsException if RSA signing fails or config is invalid
     */
    public ConnectIpsFormPayload buildFormPayload(
            String txnId,
            long txnAmt,
            String referenceId,
            String remarks,
            String particulars) {

        validateConfig();

        String txnDate = LocalDate.now().format(DATE_FORMAT);

        // Build the message string — field order is MANDATORY
        String message = buildTokenMessage(
                txnId, txnDate, txnAmt, referenceId,
                remarks, particulars
        );

        // Sign with RSA-SHA256 using CREDITOR.pfx private key
        String token = generateRsaToken(message);

        log.debug("[NepalPay] ConnectIPS form payload built | txnId={} | amt={}",
                txnId, txnAmt);

        return new ConnectIpsFormPayload(
                merchantId,
                appId,
                appName,
                txnId,
                txnDate,
                CURRENCY,
                txnAmt,
                referenceId,
                remarks,
                particulars,
                token,
                formActionUrl
        );
    }

    /**
     * Validate a ConnectIPS transaction after the user completes payment.
     *
     * <p>Call this after the ConnectIPS gateway redirects the user back.
     * Always verify server-side — redirect parameters alone can be faked.
     *
     * <p>Uses HTTP Basic Auth: username=appId, password=appPassword.
     *
     * @param txnId       The transaction ID from your original buildFormPayload call
     * @param referenceId Your original reference ID
     * @param txnAmt      Amount in paisa (must match original)
     * @return Validation response with status
     * @throws ConnectIpsException if validation fails or API returns error
     */
    public ConnectIpsValidateResponse validateTransaction(
            String txnId,
            String referenceId,
            long txnAmt) {

        validateConfig();

        // Build token for validation request
        String txnDate = LocalDate.now().format(DATE_FORMAT);
        String message = buildTokenMessage(
                txnId, txnDate, txnAmt, referenceId, "", ""
        );
        String token = generateRsaToken(message);

        ConnectIpsValidateRequest request = new ConnectIpsValidateRequest(
                merchantId, appId, referenceId, txnAmt, token
        );

        log.debug("[NepalPay] ConnectIPS validating transaction | txnId={}", txnId);

        try {
            // Basic auth: appId:appPassword
            String basicAuth = Base64.getEncoder().encodeToString(
                    (appId + ":" + appPassword).getBytes(StandardCharsets.UTF_8)
            );

            ConnectIpsValidateResponse response = restClient.post()
                    .uri(VALIDATE_PATH)
                    .header("Authorization", "Basic " + basicAuth)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error("[NepalPay] ConnectIPS validate 4xx | body={}", body);
                        throw new ConnectIpsException(
                                "ConnectIPS validation failed",
                                res.getStatusCode().value(), body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] ConnectIPS server error during validate");
                        throw new ConnectIpsException(
                                "ConnectIPS server error during validation",
                                res.getStatusCode().value(), null);
                    })
                    .body(ConnectIpsValidateResponse.class);

            if (response == null) {
                throw new ConnectIpsException(
                        "ConnectIPS returned empty validation response");
            }

            log.info("[NepalPay] ConnectIPS validation result | txnId={} | status={} | success={}",
                    txnId, response.status(), response.isPaymentSuccessful());

            return response;

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during ConnectIPS validation", e);
            throw new ConnectIpsException(
                    "Unexpected error during ConnectIPS validation: " + e.getMessage(), e);
        }
    }

    /**
     * Returns true if operating in UAT (sandbox) mode.
     *
     * @return true if UAT mode is active
     */
    public boolean isSandbox() {
        return sandbox;
    }

    /**
     * Returns the ConnectIPS gateway form action URL.
     *
     * @return UAT or production gateway URL
     */
    public String formActionUrl() {
        return formActionUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the canonical token message string.
     *
     * <p>Official ConnectIPS format (field order is MANDATORY):
     * {@code MERCHANTID=X,APPID=X,APPNAME=X,TXNID=X,TXNDATE=X,
     *          TXNCRNCY=NPR,TXNAMT=X,REFERENCEID=X,REMARKS=X,
     *          PARTICULARS=X,TOKEN=TOKEN}
     */
    private String buildTokenMessage(
            String txnId, String txnDate, long txnAmt,
            String referenceId, String remarks, String particulars) {

        return "MERCHANTID=" + merchantId
                + ",APPID=" + appId
                + ",APPNAME=" + appName
                + ",TXNID=" + txnId
                + ",TXNDATE=" + txnDate
                + ",TXNCRNCY=" + CURRENCY
                + ",TXNAMT=" + txnAmt
                + ",REFERENCEID=" + referenceId
                + ",REMARKS=" + (remarks != null ? remarks : "")
                + ",PARTICULARS=" + (particulars != null ? particulars : "")
                + "," + TOKEN_SUFFIX;
    }

    /**
     * Generate RSA-SHA256 signature using the CREDITOR.pfx private key.
     *
     * <p>ConnectIPS uses SHA256withRSA algorithm.
     * The signed bytes are Base64-encoded.
     */
    private String generateRsaToken(String message) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(
                    new java.io.ByteArrayInputStream(pfxBytes),
                    pfxPassword.toCharArray()
            );

            // Get the private key alias from the keystore
            String alias = keyStore.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(
                    alias, pfxPassword.toCharArray()
            );

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));

            byte[] signed = signature.sign();
            return Base64.getEncoder().encodeToString(signed);

        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to generate ConnectIPS RSA token. " +
                            "Ensure your .pfx file path and password are correct: "
                            + e.getMessage(), e);
        }
    }

    private void validateConfig() {
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