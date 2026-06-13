package com.example.demo.controller;

import io.nepalpay.connectips.ConnectIpsClient;
import io.nepalpay.core.connectips.model.ConnectIpsFormPayload;
import io.nepalpay.core.connectips.model.ConnectIpsPaymentRequest;
import io.nepalpay.core.connectips.model.ConnectIpsValidateResponse;
import io.nepalpay.core.exception.ConnectIpsException;
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
 * <h2>Prerequisites</h2>
 * ConnectIPS requires NCHL merchant registration before use.
 * Contact connectips@nchl.com.np or your bank to register.
 * You will receive a merchant ID, app credentials, and CREDITOR.pfx file.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>POST /api/demo/connectips/initiate → returns signed form payload</li>
 *   <li>Frontend POSTs form fields to payload.form_action_url</li>
 *   <li>User completes bank transfer on ConnectIPS</li>
 *   <li>ConnectIPS redirects to GET /api/demo/connectips/callback</li>
 *   <li>We validate server-side with validateTransaction()</li>
 * </ol>
 *
 * <h2>Important: Amount in Paisa</h2>
 * ConnectIPS uses paisa like Khalti. NPR 100 = 10000 paisa.
 * Use {@code ConnectIpsPaymentRequest.builder().amountNPR(100L)} for
 * automatic conversion.
 *
 * <h2>Security Rule</h2>
 * NEVER trust redirect parameters alone.
 * ALWAYS call {@code connectIpsClient.validateTransaction()} server-side.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/connectips")
@RequiredArgsConstructor
public class ConnectIpsDemoController {

    /**
     * ConnectIpsClient is auto-injected when nepalpay.connectips.* is configured.
     * Requires NCHL merchant registration and a .pfx certificate file.
     */
    private final ConnectIpsClient connectIpsClient;

    /**
     * Build a signed ConnectIPS form payload and return it to the frontend.
     *
     * <p>Example request:
     * <pre>
     * POST /api/demo/connectips/initiate
     * Content-Type: application/json
     *
     * {
     *   "orderId": "ORD-001",
     *   "amountNPR": 100,
     *   "productName": "Pro Plan"
     * }
     * </pre>
     *
     * @param request payment details
     * @return signed ConnectIPS form payload
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody Map<String, Object> request) {

        String orderId  = (String) request.get("orderId");
        long amountNPR  = Long.parseLong(request.get("amountNPR").toString());

        String txnId = "TXN-" + orderId + "-" + System.currentTimeMillis();

        log.info("[DEMO] ConnectIPS initiate | orderId={} | amountNPR={}",
                orderId, amountNPR);

        try {
            ConnectIpsPaymentRequest paymentRequest =
                    ConnectIpsPaymentRequest.builder()
                            .txnId(txnId)
                            // ⚠️ ConnectIPS uses PAISA — amountNPR() converts automatically
                            .amountNPR(amountNPR)
                            .referenceId(orderId)
                            .remarks("Payment for order " + orderId)
                            .particulars("NepalPay Demo")
                            .build();

            // In a real app: save txnId to your DB before returning!
            ConnectIpsFormPayload payload =
                    connectIpsClient.buildFormPayload(paymentRequest);

            log.info("[DEMO] ConnectIPS payload built | txnId={}", txnId);

            return ResponseEntity.ok(Map.of(
                    "txn_id",          txnId,
                    "payload",         payload,
                    "form_action_url", payload.formActionUrl(),
                    "message",         "POST all UPPERCASE payload fields as a form to form_action_url"
            ));

        } catch (ConnectIpsException e) {
            log.error("[DEMO] ConnectIPS initiate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Handle ConnectIPS callback after payment.
     *
     * <p>ConnectIPS redirects here after the user completes payment.
     * We ALWAYS validate server-side — never trust redirect params alone.
     *
     * <p>Example redirect:
     * <pre>
     * GET /api/demo/connectips/callback
     *     ?txnId=TXN-ORD-001-123456
     *     &referenceId=ORD-001
     *     &txnAmt=10000
     * </pre>
     *
     * @param txnId       the transaction ID from your original initiate call
     * @param referenceId your original order/reference ID
     * @param txnAmt      transaction amount in paisa
     * @return validation result
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String txnId,
            @RequestParam String referenceId,
            @RequestParam long txnAmt) {

        log.info("[DEMO] ConnectIPS callback | txnId={} | referenceId={}",
                txnId, referenceId);

        try {
            // ✅ Always validate server-side
            ConnectIpsValidateResponse response =
                    connectIpsClient.validateTransaction(txnId, referenceId, txnAmt);

            if (!response.isPaymentSuccessful()) {
                log.warn("[DEMO] ConnectIPS payment not confirmed | status={}",
                        response.status());

                return ResponseEntity.ok(Map.of(
                        "verified",    false,
                        "status",      response.status(),
                        "status_desc", response.statusDesc() != null
                                ? response.statusDesc() : "",
                        "message",     "Payment not confirmed"
                ));
            }

            // In a real app:
            // 1. Load order from DB by referenceId
            // 2. Verify amount matches
            // 3. Mark order as paid

            log.info("[DEMO] ConnectIPS payment verified | txnId={}", txnId);

            return ResponseEntity.ok(Map.of(
                    "verified",    true,
                    "status",      response.status(),
                    "status_desc", response.statusDesc() != null
                            ? response.statusDesc() : "",
                    "reference_id", response.referenceId() != null
                            ? response.referenceId() : "",
                    "message",     "Payment verified — safe to fulfil order"
            ));

        } catch (ConnectIpsException e) {
            log.error("[DEMO] ConnectIPS callback error | txnId={}", txnId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }
}