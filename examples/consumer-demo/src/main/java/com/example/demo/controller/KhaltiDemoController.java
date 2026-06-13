package com.example.demo.controller;

import com.example.demo.dto.PaymentInitiateRequest;
import io.nepalpay.khalti.KhaltiClient;
import io.nepalpay.khalti.exception.KhaltiException;
import io.nepalpay.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.khalti.model.KhaltiInitiateResponse;
import io.nepalpay.khalti.model.KhaltiLookupResponse;
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
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/demo/khalti/initiate — start a payment</li>
 *   <li>GET  /api/demo/khalti/callback — handle Khalti redirect</li>
 * </ul>
 *
 * <p>In a real app you would also:
 * <ul>
 *   <li>Save pidx to your database before returning paymentUrl</li>
 *   <li>Look up the order from your database in the callback handler</li>
 *   <li>Mark the order as paid after successful verification</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/khalti")
@RequiredArgsConstructor
public class KhaltiDemoController {

    /**
     * KhaltiClient is auto-injected by NepalPay Spring Boot Starter.
     * No @Bean, no config class — just inject and use.
     */
    private final KhaltiClient khaltiClient;

    /**
     * Step 1 — Initiate a Khalti payment.
     *
     * <p>Example request body:
     * <pre>
     * {
     *   "orderId": "ORD-001",
     *   "amountNPR": 100,
     *   "productName": "Pro Plan"
     * }
     * </pre>
     *
     * <p>Returns the pidx and paymentUrl to redirect the user to.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        log.info("[DEMO] Initiating Khalti payment | orderId={} | amountNPR={}",
                request.orderId(), request.amountNPR());

        try {
            KhaltiInitiateResponse response = khaltiClient.initiatePayment(
                    KhaltiInitiateRequest.builder()
                            .amount(request.amountNPR() * 100L) // NPR → paisa
                            .purchaseOrderId(request.orderId())
                            .purchaseOrderName(request.productName())
                            .build()
            );

            // In a real app: save response.pidx() to your database here!

            log.info("[DEMO] Khalti payment initiated | pidx={}", response.pidx());

            return ResponseEntity.ok(Map.of(
                    "success",      true,
                    "pidx",         response.pidx(),
                    "payment_url",  response.paymentUrl(),
                    "expires_in",   response.expiresIn(),
                    "message",      "Redirect user to payment_url"
            ));

        } catch (KhaltiException e) {
            log.error("[DEMO] Khalti initiate failed | status={}", e.httpStatus(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error",   e.getMessage(),
                    "status",  e.httpStatus()
            ));
        }
    }

    /**
     * Step 2 — Handle Khalti callback after payment.
     *
     * <p>Khalti redirects here with: ?pidx=xxx&status=Completed&...
     *
     * <p>NEVER trust the redirect parameters alone.
     * Always call lookupPayment(pidx) to verify server-side.
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String pidx,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String purchase_order_id) {

        log.info("[DEMO] Khalti callback received | pidx={} | redirectStatus={}",
                pidx, status);

        try {
            // ALWAYS verify server-side — the redirect can be faked!
            KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);

            if (!lookup.isPaymentSuccessful()) {
                log.warn("[DEMO] Khalti payment not successful | pidx={} | status={}",
                        pidx, lookup.status());
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "pidx",    pidx,
                        "status",  lookup.status(),
                        "message", "Payment not completed"
                ));
            }

            // In a real app:
            // 1. Look up order by pidx from your database
            // 2. Verify amount: lookup.isAmountValid(order.amountPaisa)
            // 3. Mark order as paid

            log.info("[DEMO] Khalti payment verified successfully | pidx={} | txnId={}",
                    pidx, lookup.transactionId());

            return ResponseEntity.ok(Map.of(
                    "success",        true,
                    "pidx",           pidx,
                    "status",         lookup.status(),
                    "transaction_id", lookup.transactionId(),
                    "amount_paisa",   lookup.totalAmount(),
                    "message",        "Payment successful — safe to fulfill order"
            ));

        } catch (KhaltiException e) {
            log.error("[DEMO] Khalti callback verification failed | pidx={}", pidx, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }
}