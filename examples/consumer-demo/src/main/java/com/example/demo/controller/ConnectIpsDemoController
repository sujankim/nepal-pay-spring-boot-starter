package com.example.demo.controller;

import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.connectips.exception.ConnectIpsException;
import io.nepalpay.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.connectips.model.ConnectIpsValidateResponse;
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
 * Demo controller showing ConnectIPS payment integration.
 *
 * <p>ConnectIPS requires merchant registration with NCHL and a .pfx
 * certificate file before it can be used. This demo shows the
 * correct integration pattern once you have your credentials.
 *
 * <p>To get ConnectIPS merchant credentials:
 * Contact NCHL at connectips@nchl.com.np or register via your bank.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/demo/connectips/initiate — build signed form payload</li>
 *   <li>GET  /api/demo/connectips/callback — handle ConnectIPS redirect</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/connectips")
@RequiredArgsConstructor
public class ConnectIpsDemoController {

    /**
     * ConnectIpsClient is auto-injected by NepalPay when configured.
     * Requires nepalpay.connectips.* properties to be set.
     */
    private final ConnectIpsClient connectIpsClient;

    /**
     * Step 1 — Build signed ConnectIPS form payload.
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
     * <p>Returns ConnectIpsFormPayload — your frontend POSTs all fields
     * to payload.form_action_url as a form.
     *
     * <p>Amount is in PAISA (NPR x 100) for ConnectIPS.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody Map<String, Object> request) {

        String orderId    = (String) request.get("orderId");
        long amountNPR    = Long.parseLong(request.get("amountNPR").toString());
        String txnId      = "TXN-" + orderId + "-" + System.currentTimeMillis();

        log.info("[DEMO] Initiating ConnectIPS payment | orderId={} | amountNPR={}",
                orderId, amountNPR);

        try {
            // ConnectIPS uses PAISA — multiply by 100
            long amountPaisa = amountNPR * 100L;

            ConnectIpsFormPayload payload = connectIpsClient.buildFormPayload(
                    txnId,
                    amountPaisa,
                    orderId,
                    "Payment for " + orderId,
                    "NepalPay Demo"
            );

            // In a real app: save txnId to your database here!

            log.info("[DEMO] ConnectIPS payload built | txnId={}", txnId);

            return ResponseEntity.ok(Map.of(
                    "success",          true,
                    "txn_id",           txnId,
                    "payload",          payload,
                    "message",          "POST all payload fields as a form to form_action_url",
                    "form_action_url",  payload.formActionUrl()
            ));

        } catch (ConnectIpsException e) {
            log.error("[DEMO] ConnectIPS initiate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }

    /**
     * Step 2 — Handle ConnectIPS callback after payment.
     *
     * <p>ConnectIPS redirects here after payment with query params.
     * Always validate server-side — never trust redirect alone.
     *
     * <p>Example redirect:
     * GET /api/demo/connectips/callback?txnId=TXN-001&referenceId=ORD-001&txnAmt=10000
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String txnId,
            @RequestParam String referenceId,
            @RequestParam long txnAmt) {

        log.info("[DEMO] ConnectIPS callback received | txnId={} | referenceId={}",
                txnId, referenceId);

        try {
            // Always validate server-side
            ConnectIpsValidateResponse response =
                    connectIpsClient.validateTransaction(txnId, referenceId, txnAmt);

            if (!response.isPaymentSuccessful()) {
                log.warn("[DEMO] ConnectIPS payment not confirmed | status={}",
                        response.status());
                return ResponseEntity.ok(Map.of(
                        "success",     false,
                        "status",      response.status(),
                        "status_desc", response.statusDesc(),
                        "message",     "Payment not confirmed"
                ));
            }

            // In a real app:
            // 1. Look up order by referenceId from your database
            // 2. Verify amount matches
            // 3. Mark order as paid

            log.info("[DEMO] ConnectIPS payment verified | txnId={}", txnId);

            return ResponseEntity.ok(Map.of(
                    "success",     true,
                    "status",      response.status(),
                    "status_desc", response.statusDesc(),
                    "reference_id", response.referenceId(),
                    "message",     "Payment verified — safe to fulfill order"
            ));

        } catch (ConnectIpsException e) {
            log.error("[DEMO] ConnectIPS callback failed | txnId={}", txnId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }
}