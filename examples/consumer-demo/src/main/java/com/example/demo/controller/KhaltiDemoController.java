package com.example.demo.controller;

import com.example.demo.dto.PaymentInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.core.khalti.model.KhaltiLookupResponse;
import io.nepalpay.core.exception.KhaltiException;
import io.nepalpay.khalti.KhaltiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo controller showing Khalti payment integration.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>POST /api/demo/khalti/initiate → returns pidx + payment_url</li>
 *   <li>Redirect user to payment_url</li>
 *   <li>Khalti redirects back to GET /api/demo/khalti/callback?pidx=xxx</li>
 *   <li>We verify server-side with lookupPayment(pidx)</li>
 * </ol>
 *
 * <h2>Important: Amount in Paisa</h2>
 * Khalti uses paisa (not NPR). NPR 100 = 10000 paisa.
 * Multiply by 100: {@code amountNPR * 100L}
 *
 * <h2>Security Rule</h2>
 * NEVER trust redirect parameters alone.
 * ALWAYS call {@code khaltiClient.lookupPayment(pidx)} to verify.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/khalti")
@RequiredArgsConstructor
public class KhaltiDemoController {

    /**
     * KhaltiClient is auto-injected by NepalPay Spring Boot Starter.
     * No @Bean method, no config class — just inject and use.
     */
    private final KhaltiClient khaltiClient;

    /**
     * Initiate a Khalti payment.
     *
     * <p>Example request:
     * <pre>
     * POST /api/demo/khalti/initiate
     * Content-Type: application/json
     *
     * {
     *   "orderId": "ORD-001",
     *   "amountNPR": 100,
     *   "productName": "Pro Plan"
     * }
     * </pre>
     *
     * <p>Example response:
     * <pre>
     * {
     *   "pidx": "bZQLD9wRVWo4CdESSfuSsB",
     *   "payment_url": "https://pay.khalti.com/?pidx=bZQLD9...",
     *   "expires_in": 900,
     *   "message": "Store pidx, then redirect user to payment_url"
     * }
     * </pre>
     *
     * @param request payment details
     * @return pidx and payment URL to redirect the user to
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        log.info("[DEMO] Khalti initiate | orderId={} | amountNPR={}",
                request.orderId(), request.amountNPR());

        try {
            KhaltiInitiateResponse response = khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            // ⚠️ Khalti uses PAISA — multiply NPR by 100
                            .amount(request.amountNPR() * 100L)
                            .purchaseOrderId(request.orderId())
                            .purchaseOrderName(request.productName())
                            .build()
            );

            // In a real app: save response.pidx() to your DB before returning!
            log.info("[DEMO] Khalti payment initiated | pidx={}", response.pidx());

            return ResponseEntity.ok(Map.of(
                    "pidx",         response.pidx(),
                    "payment_url",  response.paymentUrl(),
                    "expires_in",   response.expiresIn() != null ? response.expiresIn() : 900,
                    "message",      "Store pidx in DB, then redirect user to payment_url"
            ));

        } catch (KhaltiException e) {
            log.error("[DEMO] Khalti initiate failed | status={} | body={}",
                    e.httpStatus(), e.responseBody());
            return ResponseEntity.badRequest().body(Map.of(
                    "error",       e.getMessage(),
                    "http_status", e.httpStatus()
            ));
        }
    }

    /**
     * Handle Khalti callback after payment.
     *
     * <p>Khalti redirects here with query parameters after payment.
     * We ALWAYS verify server-side — never trust redirect params alone.
     *
     * <p>Example redirect:
     * <pre>
     * GET /api/demo/khalti/callback
     *     ?pidx=bZQLD9wRVWo4CdESSfuSsB
     *     &status=Completed
     *     &purchase_order_id=ORD-001
     * </pre>
     *
     * @param pidx              payment identifier from Khalti
     * @param status            raw status from redirect (do NOT trust this alone!)
     * @param purchaseOrderId   your order ID echoed back
     * @return verification result
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String pidx,
            @RequestParam(required = false) String status,
            @RequestParam(name = "purchase_order_id", required = false)
            String purchaseOrderId) {

        log.info("[DEMO] Khalti callback received | pidx={} | redirectStatus={}",
                pidx, status);

        try {
            // ✅ ALWAYS verify server-side — redirect parameters can be faked!
            KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);

            if (!lookup.isPaymentSuccessful()) {
                log.warn("[DEMO] Khalti payment not confirmed | pidx={} | status={}",
                        pidx, lookup.status());

                return ResponseEntity.ok(Map.of(
                        "verified",  false,
                        "pidx",      pidx,
                        "status",    lookup.status(),
                        "message",   "Payment not completed — status: " + lookup.status()
                ));
            }

            // In a real app:
            // 1. Load order from DB by pidx (saved during initiate)
            // 2. Verify amount: lookup.isAmountValid(order.amountPaisa())
            // 3. Mark order as paid
            // 4. Redirect user to success page

            log.info("[DEMO] Khalti payment verified | pidx={} | txnId={}",
                    pidx, lookup.transactionId());

            return ResponseEntity.ok(Map.of(
                    "verified",       true,
                    "pidx",           pidx,
                    "status",         lookup.status(),
                    "transaction_id", lookup.transactionId() != null ? lookup.transactionId() : "",
                    "amount_paisa",   lookup.totalAmount() != null ? lookup.totalAmount() : 0,
                    "message",        "Payment verified — safe to fulfil order"
            ));

        } catch (KhaltiException e) {
            log.error("[DEMO] Khalti callback error | pidx={}", pidx, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }
}