package com.example.demo.controller;

import com.example.demo.dto.PaymentInitiateRequest;
import io.nepalpay.core.esewa.model.EsewaStatusResponse;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.khalti.model.KhaltiRefundResponse;
import io.nepalpay.reactive.esewa.EsewaReactiveClient;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Demo controller showing reactive payment integration.
 *
 * <p>Uses {@link KhaltiReactiveClient} and {@link EsewaReactiveClient}
 * from {@code nepal-pay-spring-boot-reactive-starter}.
 *
 * <p>All methods return {@code Mono<ResponseEntity<...>>} — fully
 * non-blocking reactive pipeline from controller to gateway API.
 *
 * <h2>Key reactive rules demonstrated here</h2>
 * <ol>
 *   <li>Validation errors are emitted as Mono signals — never thrown</li>
 *   <li>All HTTP calls are non-blocking via WebClient</li>
 *   <li>Errors handled with {@code onErrorResume} — no try/catch</li>
 *   <li>{@code doOnNext} saves identifiers before continuing pipeline</li>
 * </ol>
 *
 * <h2>Endpoints</h2>
 * <pre>
 * POST /api/demo/reactive/khalti/initiate
 * GET  /api/demo/reactive/khalti/lookup?pidx=xxx
 * POST /api/demo/reactive/khalti/refund
 * GET  /api/demo/reactive/esewa/status?uuid=xxx&amp;amount=100.00
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/reactive")
@RequiredArgsConstructor
public class ReactivePaymentController {

    /**
     * KhaltiReactiveClient auto-injected from reactive starter.
     * Returns Mono&lt;T&gt; — non-blocking WebClient internally.
     */
    private final KhaltiReactiveClient khaltiReactiveClient;

    /**
     * EsewaReactiveClient auto-injected from reactive starter.
     * Returns Mono&lt;T&gt; — non-blocking WebClient internally.
     */
    private final EsewaReactiveClient esewaReactiveClient;

    // ─────────────────────────────────────────────────────────────────────
    // KHALTI — REACTIVE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initiate a Khalti payment — reactive pipeline.
     *
     * <p>Example request:
     * <pre>
     * POST /api/demo/reactive/khalti/initiate
     * {"orderId":"ORD-R01","amountNPR":100,"productName":"Pro Plan"}
     * </pre>
     *
     * <p>Demonstrates:
     * <ul>
     *   <li>{@code doOnNext} — save pidx before continuing</li>
     *   <li>{@code map} — transform response to API output</li>
     *   <li>{@code onErrorResume} — handle KhaltiException reactively</li>
     * </ul>
     *
     * @param request payment details
     * @return Mono of pidx + payment_url
     */
    @PostMapping("/khalti/initiate")
    public Mono<ResponseEntity<Map<String, Object>>> initiateKhalti(
            @RequestBody PaymentInitiateRequest request) {

        log.info("[DEMO Reactive] Khalti initiate | orderId={} | amountNPR={}",
                request.orderId(), request.amountNPR());

        return khaltiReactiveClient.initiatePayment(
                        KhaltiInitiateRequest.builder()
                                // ⚠️ Khalti uses PAISA — multiply NPR by 100
                                .amount(request.amountNPR() * 100L)
                                .purchaseOrderId(request.orderId())
                                .purchaseOrderName(request.productName())
                                .build()
                )
                // In a real app: save pidx to DB before continuing
                .doOnNext(res -> log.info(
                        "[DEMO Reactive] Khalti pidx={} | orderId={}",
                        res.pidx(), request.orderId()))
                .map(res -> ResponseEntity.ok(Map.<String, Object>of(
                        "pidx",        res.pidx(),
                        "payment_url", res.paymentUrl(),
                        "expires_in",  res.expiresIn() != null
                                ? res.expiresIn() : 900,
                        "message",     "Reactive pipeline — redirect user to payment_url"
                )))
                .onErrorResume(KhaltiException.class, ex -> {
                    log.error("[DEMO Reactive] Khalti initiate failed" +
                                    " | status={} | body={}",
                            ex.httpStatus(), ex.responseBody());
                    return Mono.just(ResponseEntity.badRequest()
                            .body(Map.<String, Object>of(
                                    "error",       ex.getMessage(),
                                    "http_status", ex.httpStatus()
                            )));
                });
    }

    /**
     * Lookup a Khalti payment by pidx — reactive pipeline.
     *
     * <p>Example:
     * <pre>GET /api/demo/reactive/khalti/lookup?pidx=bZQLD9wRVWo4CdESSfuSsB</pre>
     *
     * <p>Demonstrates:
     * <ul>
     *   <li>{@code flatMap} — branch on payment status</li>
     *   <li>{@code onErrorResume} — typed exception handling</li>
     * </ul>
     *
     * @param pidx payment identifier from initiate
     * @return Mono of verification result
     */
    @GetMapping("/khalti/lookup")
    public Mono<ResponseEntity<Map<String, Object>>> lookupKhalti(
            @RequestParam String pidx) {

        log.info("[DEMO Reactive] Khalti lookup | pidx={}", pidx);

        // ✅ Validation error is emitted as Mono.error — not thrown
        return khaltiReactiveClient.lookupPayment(pidx)
                .flatMap(lookup -> {
                    if (!lookup.isPaymentSuccessful()) {
                        log.warn("[DEMO Reactive] Khalti not confirmed" +
                                        " | pidx={} | status={}",
                                pidx, lookup.status());
                        return Mono.just(ResponseEntity.ok(
                                Map.<String, Object>of(
                                        "verified", false,
                                        "pidx",     pidx,
                                        "status",   lookup.status(),
                                        "message",  "Payment not completed"
                                )));
                    }

                    // In a real app: mark order as paid here
                    log.info("[DEMO Reactive] Khalti verified" +
                                    " | pidx={} | txnId={}",
                            pidx, lookup.transactionId());

                    return Mono.just(ResponseEntity.ok(
                            Map.<String, Object>of(
                                    "verified",       true,
                                    "pidx",           pidx,
                                    "status",         lookup.status(),
                                    "transaction_id", lookup.transactionId() != null
                                            ? lookup.transactionId() : "",
                                    "amount_paisa",   lookup.totalAmount() != null
                                            ? lookup.totalAmount() : 0L,
                                    "message",        "Payment verified — safe to fulfil"
                            )));
                })
                .onErrorResume(KhaltiException.class, ex ->
                        Mono.just(ResponseEntity.internalServerError()
                                .body(Map.<String, Object>of(
                                        "error",       ex.getMessage(),
                                        "http_status", ex.httpStatus()
                                ))));
    }

    /**
     * Refund a completed Khalti payment — reactive pipeline.
     *
     * <p>Example full refund:
     * <pre>{"transactionId":"GFq9DrfGSZQKjsj"}</pre>
     *
     * <p>Example partial refund:
     * <pre>{"transactionId":"GFq9DrfGSZQKjsj","amountPaisa":5000}</pre>
     *
     * <p>Demonstrates reactive refund with {@code flatMap} branching.
     *
     * @param request refund request with transactionId and optional amountPaisa
     * @return Mono of refund result
     */
    @PostMapping("/khalti/refund")
    public Mono<ResponseEntity<Map<String, Object>>> refundKhalti(
            @RequestBody Map<String, Object> request) {

        String transactionId = (String) request.get("transactionId");
        Object amountValue   = request.get("amountPaisa");
        Long amountPaisa     = amountValue != null
                ? Long.parseLong(amountValue.toString()) : null;

        log.info("[DEMO Reactive] Khalti refund | txnId={} | type={}",
                transactionId,
                amountPaisa != null
                        ? "PARTIAL " + amountPaisa + " paisa" : "FULL");

        Mono<KhaltiRefundResponse> refundMono = amountPaisa != null
                ? khaltiReactiveClient.refundPayment(transactionId, amountPaisa)
                : khaltiReactiveClient.refundPayment(transactionId);

        return refundMono
                .flatMap(refund -> {
                    if (!refund.isRefundSuccessful()) {
                        return Mono.just(ResponseEntity.ok(
                                Map.<String, Object>of(
                                        "refunded", false,
                                        "status",   refund.status() != null
                                                ? refund.status() : "",
                                        "message",  "Refund not confirmed by Khalti"
                                )));
                    }

                    log.info("[DEMO Reactive] Khalti refund successful" +
                            " | txnId={}", transactionId);

                    return Mono.just(ResponseEntity.ok(
                            Map.<String, Object>of(
                                    "refunded",       true,
                                    "transaction_id", refund.transactionId() != null
                                            ? refund.transactionId() : "",
                                    "pidx",           refund.pidx() != null
                                            ? refund.pidx() : "",
                                    "status",         refund.status() != null
                                            ? refund.status() : "",
                                    "message",        amountPaisa != null
                                            ? "Partial refund successful"
                                            : "Full refund successful"
                            )));
                })
                .onErrorResume(KhaltiException.class, ex -> {
                    log.error("[DEMO Reactive] Khalti refund failed" +
                                    " | txnId={} | status={}",
                            transactionId, ex.httpStatus());
                    return Mono.just(ResponseEntity.badRequest()
                            .body(Map.<String, Object>of(
                                    "error",       ex.getMessage(),
                                    "http_status", ex.httpStatus()
                            )));
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // ESEWA — REACTIVE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check eSewa transaction status — reactive pipeline.
     *
     * <p>Example:
     * <pre>
     * GET /api/demo/reactive/esewa/status
     *     ?uuid=test-uuid-001&amp;amount=100.00
     * </pre>
     *
     * <p>Demonstrates:
     * <ul>
     *   <li>Reactive {@code checkStatus()} — non-blocking HTTP GET</li>
     *   <li>Blank-input validation emitted as Mono.error</li>
     *   <li>{@code onErrorResume} for typed EsewaException</li>
     * </ul>
     *
     * @param uuid   transaction UUID saved during eSewa form initiation
     * @param amount exact total amount used during initiation
     * @return Mono of status result
     */
    @GetMapping("/esewa/status")
    public Mono<ResponseEntity<Map<String, Object>>> checkEsewaStatus(
            @RequestParam String uuid,
            @RequestParam String amount) {

        log.info("[DEMO Reactive] eSewa status check" +
                " | uuid={} | amount={}", uuid, amount);

        return esewaReactiveClient.checkStatus(uuid, amount)
                .map(res -> ResponseEntity.ok(Map.<String, Object>of(
                        "verified",          res.isPaymentSuccessful(),
                        "status",            res.status() != null
                                ? res.status() : "",
                        "transaction_uuid",  res.transactionUuid() != null
                                ? res.transactionUuid() : "",
                        "ref_id",            res.refId() != null
                                ? res.refId() : "",
                        "message",           res.isPaymentSuccessful()
                                ? "Payment confirmed — safe to fulfil"
                                : "Payment not confirmed"
                )))
                .onErrorResume(EsewaException.class, ex -> {
                    log.error("[DEMO Reactive] eSewa status failed" +
                                    " | uuid={} | status={}",
                            uuid, ex.httpStatus());
                    return Mono.just(ResponseEntity.badRequest()
                            .body(Map.<String, Object>of(
                                    "error",       ex.getMessage(),
                                    "http_status", ex.httpStatus()
                            )));
                });
    }
}