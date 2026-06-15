package io.nepalpay.khalti;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Khalti Payment Gateway Client for Spring Boot 3.
 *
 * <p>Provides three operations:
 * <ul>
 *   <li>{@link #initiatePayment}  — Start payment, get redirect URL</li>
 *   <li>{@link #lookupPayment}    — Verify payment after callback</li>
 *   <li>{@link #refundPayment}    — Refund a completed payment</li>
 * </ul>
 *
 * <p>Official Khalti docs: https://docs.khalti.com/khalti-epayment/
 *
 * <p><strong>URL structure note:</strong>
 * Khalti uses two different API path prefixes:
 * <ul>
 *   <li>Initiate + Lookup: {@code /api/v2/epayment/...}</li>
 *   <li>Refund:            {@code /api/merchant-transaction/{id}/refund/}</li>
 * </ul>
 * The refund path has no {@code /api/v2} segment — this is intentional
 * in Khalti's API design. We handle this by storing a separate
 * {@code baseDomain} field for building the refund URL.
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiClient {

    // ── Official Khalti API base URLs (initiate + lookup) ─────────────────────
    private static final String SANDBOX_BASE_URL    = "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL = "https://khalti.com/api/v2";

    // ── Base domains — used to build the refund URL ────────────────────────────
    // Refund path: /api/merchant-transaction/{id}/refund/
    // Note: NO "/api/v2" in refund path — different API tree in Khalti's design
    private static final String SANDBOX_BASE_DOMAIN    = "https://dev.khalti.com";
    private static final String PRODUCTION_BASE_DOMAIN = "https://khalti.com";

    // ── Paths ─────────────────────────────────────────────────────────────────
    private static final String INITIATE_PATH = "/epayment/initiate/";
    private static final String LOOKUP_PATH   = "/epayment/lookup/";

    // ── Refund path template — transactionId is interpolated at runtime ───────
    // Full URL example:
    //   https://dev.khalti.com/api/merchant-transaction/GFq9DrfGSZQKjsj/refund/
    private static final String REFUND_PATH_PREFIX = "/api/merchant-transaction/";
    private static final String REFUND_PATH_SUFFIX = "/refund/";

    private final NepalPayProperties.KhaltiProperties props;
    private final RestClient restClient;
    private final String baseUrl;
    private final String baseDomain;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by auto-configuration.
     *
     * <p>Base URL and base domain are automatically determined
     * from {@code nepalpay.khalti.sandbox}:
     * <ul>
     *   <li>sandbox=true  → dev.khalti.com</li>
     *   <li>sandbox=false → khalti.com (production)</li>
     * </ul>
     *
     * @param props             Khalti properties from application.yml
     * @param restClientBuilder Spring Boot's RestClient builder
     */
    public KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder) {

        this(
                props,
                restClientBuilder,
                props.sandbox() ? SANDBOX_BASE_URL    : PRODUCTION_BASE_URL,
                props.sandbox() ? SANDBOX_BASE_DOMAIN : PRODUCTION_BASE_DOMAIN
        );
    }

    /**
     * Test constructor — allows injecting a custom base URL.
     *
     * <p>Used in tests to point the client at a {@code MockWebServer}
     * URL instead of the real Khalti API.
     *
     * <p>The {@code baseUrlOverride} is used for BOTH the API v2
     * base path AND the refund base domain — this means all three
     * endpoints (initiate, lookup, refund) hit MockWebServer correctly.
     *
     * <p>Example test usage:
     * <pre>{@code
     * KhaltiClient client = new KhaltiClient(
     *     props,
     *     RestClient.builder(),
     *     mockWebServer.url("/").toString()
     * );
     * }</pre>
     *
     * @param props             Khalti properties
     * @param restClientBuilder RestClient builder
     * @param baseUrlOverride   Custom base URL (e.g. MockWebServer URL)
     */
    public KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder,
            String baseUrlOverride) {

        this(
                props,
                restClientBuilder,
                baseUrlOverride,
                // Strip trailing slash for baseDomain — prevents double-slash
                // in refund URL: "http://localhost:PORT//api/merchant-transaction/..."
                baseUrlOverride.endsWith("/")
                        ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1)
                        : baseUrlOverride
        );
    }

    /**
     * Core private constructor — all fields initialized here.
     *
     * <p>All public constructors delegate to this one.
     * This is the single point where RestClient is built.
     *
     * @param props             Khalti properties
     * @param restClientBuilder RestClient builder
     * @param baseUrl           Full API v2 base URL (for initiate + lookup)
     * @param baseDomain        Domain only — used to build refund URL
     */
    private KhaltiClient(
            NepalPayProperties.KhaltiProperties props,
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String baseDomain) {

        this.props      = props;
        this.baseUrl    = baseUrl;
        this.baseDomain = baseDomain;

        this.restClient = restClientBuilder
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Key " + props.secretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] KhaltiClient initialized | mode={} | baseUrl={} | baseDomain={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                this.baseUrl,
                this.baseDomain);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment.
     *
     * <p>Makes a server-to-server POST to Khalti and returns a
     * {@code paymentUrl} to redirect the user to.
     *
     * <p><strong>Important:</strong> Store the {@code pidx} from the
     * response in your database BEFORE redirecting the user.
     * You need it to verify the payment after the user returns.
     *
     * @param request Payment details — use
     *                {@link KhaltiInitiateRequest#builder()}
     * @return Response with {@code pidx} and {@code paymentUrl}
     * @throws KhaltiException if validation fails or Khalti API returns an error
     */
    public KhaltiInitiateResponse initiatePayment(KhaltiInitiateRequest request) {
        validateInitiateRequest(request);

        KhaltiInitiateRequest finalRequest = withDefaults(request);

        log.debug("[NepalPay] Khalti initiate | orderId={} | amount={} paisa",
                finalRequest.purchaseOrderId(),
                finalRequest.amount());

        try {
            KhaltiInitiateResponse response = restClient.post()
                    .uri(INITIATE_PATH)
                    .body(finalRequest)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error("[NepalPay] Khalti initiate failed | status={} | body={}",
                                res.getStatusCode().value(), body);
                        throw new KhaltiException(
                                "Khalti payment initiation failed — " +
                                        "check your secret key or request",
                                res.getStatusCode().value(),
                                body
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] Khalti server error | status={}",
                                res.getStatusCode().value());
                        throw new KhaltiException(
                                "Khalti server error — please try again later",
                                res.getStatusCode().value(),
                                null
                        );
                    })
                    .body(KhaltiInitiateResponse.class);

            if (response == null) {
                throw new KhaltiException(
                        "Khalti returned empty response for initiate");
            }

            log.info("[NepalPay] Khalti payment initiated | pidx={} | orderId={}",
                    response.pidx(),
                    finalRequest.purchaseOrderId());

            return response;

        } catch (KhaltiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during Khalti initiate", e);
            throw new KhaltiException(
                    "Unexpected error initiating Khalti payment: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Lookup (verify) a Khalti payment by its {@code pidx}.
     *
     * <p>ALWAYS call this after receiving the callback redirect.
     * The redirect URL alone can be faked. This server-side lookup
     * is the ONLY reliable way to confirm a payment.
     *
     * <p>Official rule: Only {@code status = "Completed"} means success.
     *
     * <p>If you need to refund a completed payment, use the
     * {@code transactionId} from this response — see
     * {@link #refundPayment(String)}.
     *
     * @param pidx The payment identifier from
     *             {@link #initiatePayment} response
     * @return Verified payment details including status and amount
     * @throws KhaltiException if pidx is invalid or API call fails
     */
    public KhaltiLookupResponse lookupPayment(String pidx) {
        if (pidx == null || pidx.isBlank()) {
            throw new KhaltiException("pidx cannot be null or blank");
        }

        log.debug("[NepalPay] Khalti lookup | pidx={}", pidx);

        try {
            KhaltiLookupResponse response = restClient.post()
                    .uri(LOOKUP_PATH)
                    .body(Map.of("pidx", pidx))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error(
                                "[NepalPay] Khalti lookup failed | pidx={} " +
                                        "| status={} | body={}",
                                pidx, res.getStatusCode().value(), body);
                        throw new KhaltiException(
                                "Khalti lookup failed — invalid pidx or unauthorized",
                                res.getStatusCode().value(),
                                body
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error(
                                "[NepalPay] Khalti server error during lookup " +
                                        "| pidx={}", pidx);
                        throw new KhaltiException(
                                "Khalti server error during lookup — try again",
                                res.getStatusCode().value(),
                                null
                        );
                    })
                    .body(KhaltiLookupResponse.class);

            if (response == null) {
                throw new KhaltiException(
                        "Khalti returned empty response for lookup pidx=" + pidx);
            }

            log.info("[NepalPay] Khalti lookup result | pidx={} | status={} " +
                            "| success={}",
                    response.pidx(),
                    response.status(),
                    response.isPaymentSuccessful());

            return response;

        } catch (KhaltiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during Khalti lookup " +
                    "| pidx={}", pidx, e);
            throw new KhaltiException(
                    "Unexpected error during Khalti lookup: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Refund a completed Khalti payment — FULL refund.
     *
     * <p>Refunds the entire original transaction amount back to
     * the customer.
     *
     * <p><strong>You need {@code transactionId}, NOT {@code pidx}.</strong>
     * Get {@code transactionId} from
     * {@link #lookupPayment(String)} response. It is only non-null
     * after a payment reaches {@code Completed} status.
     *
     * <p>Correct usage:
     * <pre>{@code
     * // Step 1: look up the payment first
     * KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);
     *
     * // Step 2: get the transactionId (non-null only when Completed)
     * String txnId = lookup.transactionId();
     *
     * // Step 3: refund using transactionId
     * KhaltiRefundResponse refund = khaltiClient.refundPayment(txnId);
     * if (refund.isRefundSuccessful()) { ... }
     * }</pre>
     *
     * @param transactionId Khalti's internal transaction ID
     *                      (from {@link KhaltiLookupResponse#transactionId()})
     * @return Refund confirmation response
     * @throws KhaltiException if transactionId is blank, payment is
     *                         not refundable, or API call fails
     */
    public KhaltiRefundResponse refundPayment(String transactionId) {
        return executeRefundRequest(transactionId, null);
    }

    /**
     * Refund a completed Khalti payment — PARTIAL refund.
     *
     * <p>Refunds a specific amount (less than the original) back
     * to the customer. The remaining balance stays with the merchant.
     *
     * <p>Amount must be in PAISA (NPR × 100) and must not exceed
     * the original transaction amount.
     *
     * <p>Example — refund NPR 50 from a NPR 100 transaction:
     * <pre>{@code
     * KhaltiRefundResponse refund =
     *     khaltiClient.refundPayment(transactionId, 5000L); // NPR 50
     * }</pre>
     *
     * @param transactionId Khalti's internal transaction ID
     *                      (from {@link KhaltiLookupResponse#transactionId()})
     * @param amountPaisa   Amount to refund in PAISA (NPR × 100).
     *                      Pass {@code null} to do a full refund instead.
     * @return Refund confirmation response
     * @throws KhaltiException if transactionId is blank, amount is
     *                         invalid, or API call fails
     */
    public KhaltiRefundResponse refundPayment(
            String transactionId,
            Long amountPaisa) {

        if (amountPaisa != null && amountPaisa <= 0) {
            throw new KhaltiException(
                    "amountPaisa must be greater than 0 for partial refund. " +
                            "Got: " + amountPaisa + ". " +
                            "Use refundPayment(transactionId) for a full refund."
            );
        }

        return executeRefundRequest(transactionId, amountPaisa);
    }

    /**
     * Returns true if operating in sandbox (test) mode.
     *
     * @return true if sandbox mode is active
     */
    public boolean isSandbox() {
        return props.sandbox();
    }

    /**
     * Returns the current active API v2 base URL.
     * Used for initiate and lookup endpoints.
     *
     * @return sandbox or production API v2 base URL
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the current active base domain.
     * Used to build the refund endpoint URL.
     *
     * @return sandbox or production base domain
     */
    public String baseDomain() {
        return baseDomain;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Core refund execution — shared by both public refundPayment() overloads.
     *
     * <p>Builds the refund URL using {@code baseDomain} (not the v2 baseUrl)
     * because Khalti's refund endpoint uses a completely different path prefix:
     * {@code /api/merchant-transaction/{id}/refund/}
     * vs the v2 endpoints:
     * {@code /api/v2/epayment/...}
     *
     * <p>Request body:
     * <ul>
     *   <li>Full refund ({@code amountPaisa=null}): empty body {@code {}}</li>
     *   <li>Partial refund: {@code {"amount": 5000}}</li>
     * </ul>
     *
     * @param transactionId Khalti transaction ID
     * @param amountPaisa   null = full refund, non-null = partial refund
     * @return refund confirmation response
     */
    private KhaltiRefundResponse executeRefundRequest(
            String transactionId,
            Long amountPaisa) {

        if (transactionId == null || transactionId.isBlank()) {
            throw new KhaltiException(
                    "transactionId cannot be null or blank. " +
                            "Obtain transactionId from lookupPayment() " +
                            "after a payment reaches Completed status. " +
                            "transactionId is null for Pending/Expired/Canceled payments."
            );
        }

        // Build full refund URL using baseDomain
        // Example: https://dev.khalti.com/api/merchant-transaction/GFq9DrfG/refund/
        String refundUrl = baseDomain
                + REFUND_PATH_PREFIX
                + transactionId
                + REFUND_PATH_SUFFIX;

        // Full refund = empty body {}
        // Partial refund = {"amount": 5000}
        Object requestBody = (amountPaisa != null)
                ? Map.of("amount", amountPaisa)
                : Map.of();

        log.debug("[NepalPay] Khalti refund request | txnId={} | type={} | url={}",
                transactionId,
                amountPaisa != null ? "PARTIAL (" + amountPaisa + " paisa)" : "FULL",
                refundUrl);

        try {
            KhaltiRefundResponse response = restClient.post()
                    .uri(refundUrl)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = readBodySafely(res);
                        log.error(
                                "[NepalPay] Khalti refund 4xx | txnId={} " +
                                        "| status={} | body={}",
                                transactionId,
                                res.getStatusCode().value(),
                                body);
                        throw new KhaltiException(
                                "Khalti refund failed — " +
                                        "payment may not be in a refundable state. " +
                                        "Only Completed payments can be refunded.",
                                res.getStatusCode().value(),
                                body
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error(
                                "[NepalPay] Khalti server error during refund " +
                                        "| txnId={}", transactionId);
                        throw new KhaltiException(
                                "Khalti server error during refund — try again",
                                res.getStatusCode().value(),
                                null
                        );
                    })
                    .body(KhaltiRefundResponse.class);

            if (response == null) {
                throw new KhaltiException(
                        "Khalti returned empty response for refund " +
                                "txnId=" + transactionId);
            }

            log.info("[NepalPay] Khalti refund result | txnId={} | status={} " +
                            "| refunded={}",
                    transactionId,
                    response.status(),
                    response.isRefundSuccessful());

            return response;

        } catch (KhaltiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during Khalti refund " +
                    "| txnId={}", transactionId, e);
            throw new KhaltiException(
                    "Unexpected error during Khalti refund: "
                            + e.getMessage(), e);
        }
    }

    private void validateInitiateRequest(KhaltiInitiateRequest req) {
        if (req == null) {
            throw new KhaltiException(
                    "KhaltiInitiateRequest cannot be null");
        }
        if (req.amount() <= 0) {
            throw new KhaltiException(
                    "Amount must be greater than 0 paisa. Got: " + req.amount() +
                            ". Reminder: NPR x 100 = paisa " +
                            "(minimum NPR 10 = 1000 paisa)"
            );
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
        String returnUrl  = (req.returnUrl()  != null)
                ? req.returnUrl()  : props.returnUrl();
        String websiteUrl = (req.websiteUrl() != null)
                ? req.websiteUrl() : props.websiteUrl();

        if (returnUrl == null || returnUrl.isBlank()) {
            throw new KhaltiException(
                    "returnUrl is required. " +
                            "Set it in the request or via " +
                            "nepalpay.khalti.return-url in application.yml"
            );
        }

        return KhaltiInitiateRequest.builder()
                .amount(req.amount())
                .purchaseOrderId(req.purchaseOrderId())
                .purchaseOrderName(req.purchaseOrderName())
                .returnUrl(returnUrl)
                .websiteUrl(websiteUrl)
                .build();
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