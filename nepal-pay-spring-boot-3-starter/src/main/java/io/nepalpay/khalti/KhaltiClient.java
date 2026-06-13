package io.nepalpay.khalti;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Khalti Payment Gateway Client for Spring Boot 4.1.0.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #initiatePayment} — Start payment, get redirect URL</li>
 *   <li>{@link #lookupPayment} — Verify payment status (ALWAYS call after callback!)</li>
 * </ul>
 *
 * <p>Official Khalti docs: https://docs.khalti.com/khalti-epayment/
 *
 * <p>This bean is auto-configured when {@code nepalpay.khalti.secret-key} is present.
 *
 * <pre>{@code
 * @Service
 * public class PaymentService {
 *     private final KhaltiClient khaltiClient;
 *
 *     public String startPayment(long amountNPR, String orderId) {
 *         var res = khaltiClient.initiatePayment(
 *             KhaltiInitiateRequest.builder()
 *                 .amount(amountNPR * 100)
 *                 .purchaseOrderId(orderId)
 *                 .purchaseOrderName("Your Product")
 *                 .build()
 *         );
 *         return res.paymentUrl();
 *     }
 * }
 * }</pre>
 *
 * @author Sujan Lamichhane
 */
@Slf4j
public final class KhaltiClient {

    // Official Khalti API base URLs
    private static final String SANDBOX_BASE_URL    = "https://dev.khalti.com/api/v2";
    private static final String PRODUCTION_BASE_URL = "https://khalti.com/api/v2";

    private static final String INITIATE_PATH = "/epayment/initiate/";
    private static final String LOOKUP_PATH   = "/epayment/lookup/";

    private final NepalPayProperties.KhaltiProperties props;
    private final RestClient restClient;
    private final String baseUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Production constructor — used by {@link io.nepalpay.autoconfigure.NepalPayAutoConfiguration}.
     *
     * <p>Base URL is automatically determined from {@code nepalpay.khalti.sandbox}:
     * <ul>
     *   <li>sandbox=true  → https://dev.khalti.com/api/v2</li>
     *   <li>sandbox=false → https://khalti.com/api/v2</li>
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
                props.sandbox() ? SANDBOX_BASE_URL : PRODUCTION_BASE_URL
        );
    }

    /**
     * Test constructor — allows injecting a custom base URL.
     *
     * <p>Used in tests to point the client at a {@code MockWebServer} URL
     * instead of the real Khalti API.
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

        this.props   = props;
        this.baseUrl = baseUrlOverride;

        this.restClient = restClientBuilder
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Key " + props.secretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("[NepalPay] KhaltiClient initialized | mode={} | baseUrl={}",
                props.sandbox() ? "SANDBOX" : "PRODUCTION",
                this.baseUrl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment.
     *
     * <p>Makes a server-to-server POST to Khalti and returns a {@code paymentUrl}
     * to redirect the user to. Store the {@code pidx} in your database —
     * you will need it to verify the payment after the user returns.
     *
     * @param request Payment details — use {@link KhaltiInitiateRequest#builder()}
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
                                "Khalti payment initiation failed — check your secret key or request",
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
                throw new KhaltiException("Khalti returned empty response for initiate");
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
                    "Unexpected error initiating Khalti payment: " + e.getMessage(), e);
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
     * @param pidx The payment identifier from {@link #initiatePayment} response
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
                        log.error("[NepalPay] Khalti lookup failed | pidx={} | status={} | body={}",
                                pidx, res.getStatusCode().value(), body);
                        throw new KhaltiException(
                                "Khalti lookup failed — invalid pidx or unauthorized",
                                res.getStatusCode().value(),
                                body
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        log.error("[NepalPay] Khalti server error during lookup | pidx={}", pidx);
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

            log.info("[NepalPay] Khalti lookup result | pidx={} | status={} | success={}",
                    response.pidx(),
                    response.status(),
                    response.isPaymentSuccessful());

            return response;

        } catch (KhaltiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NepalPay] Unexpected error during Khalti lookup | pidx={}", pidx, e);
            throw new KhaltiException(
                    "Unexpected error during Khalti lookup: " + e.getMessage(), e);
        }
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
     * Returns the current active base URL.
     *
     * @return sandbox or production base URL
     */
    public String baseUrl() {
        return baseUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void validateInitiateRequest(KhaltiInitiateRequest req) {
        if (req == null) {
            throw new KhaltiException("KhaltiInitiateRequest cannot be null");
        }
        if (req.amount() <= 0) {
            throw new KhaltiException(
                    "Amount must be greater than 0 paisa. Got: " + req.amount() +
                            ". Reminder: NPR x 100 = paisa (minimum NPR 10 = 1000 paisa)"
            );
        }
        if (req.purchaseOrderId() == null || req.purchaseOrderId().isBlank()) {
            throw new KhaltiException("purchaseOrderId is required and cannot be blank");
        }
        if (req.purchaseOrderName() == null || req.purchaseOrderName().isBlank()) {
            throw new KhaltiException("purchaseOrderName is required and cannot be blank");
        }
    }

    private KhaltiInitiateRequest withDefaults(KhaltiInitiateRequest req) {
        String returnUrl  = (req.returnUrl()  != null) ? req.returnUrl()  : props.returnUrl();
        String websiteUrl = (req.websiteUrl() != null) ? req.websiteUrl() : props.websiteUrl();

        if (returnUrl == null || returnUrl.isBlank()) {
            throw new KhaltiException(
                    "returnUrl is required. " +
                            "Set it in the request or via nepalpay.khalti.return-url in application.yml"
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

    private String readBodySafely(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes());
        } catch (Exception ignored) {
            return "<unable to read response body>";
        }
    }
}