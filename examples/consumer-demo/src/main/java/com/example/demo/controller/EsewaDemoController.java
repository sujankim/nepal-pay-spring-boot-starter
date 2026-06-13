package com.example.demo.controller;

import com.example.demo.dto.PaymentInitiateRequest;
import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.esewa.exception.EsewaException;
import io.nepalpay.esewa.model.EsewaFormPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Demo controller showing eSewa payment integration.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/demo/esewa/initiate — build signed form payload</li>
 *   <li>GET  /api/demo/esewa/callback — verify eSewa redirect</li>
 *   <li>GET  /api/demo/esewa/failed   — handle eSewa failure redirect</li>
 * </ul>
 *
 * <p>eSewa uses a FORM-SUBMISSION model.
 * Your backend builds a signed payload and returns it to the frontend.
 * The frontend POSTs the form directly to eSewa.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/esewa")
@RequiredArgsConstructor
public class EsewaDemoController {

    /**
     * EsewaClient is auto-injected by NepalPay Spring Boot Starter.
     * No @Bean, no config class — just inject and use.
     */
    private final EsewaClient esewaClient;

    /**
     * Step 1 — Build signed eSewa form payload.
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
     * <p>Returns EsewaFormPayload — your frontend POSTs this
     * as a form to payload.form_action_url.
     *
     * <p>NOTE: eSewa uses NPR directly — not paisa like Khalti.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        log.info("[DEMO] Initiating eSewa payment | orderId={} | amountNPR={}",
                request.orderId(), request.amountNPR());

        try {
            // Generate a unique transaction UUID
            // In a real app: save this UUID to your database BEFORE returning!
            String transactionUuid = EsewaClient.generateTransactionUuid();

            log.info("[DEMO] eSewa transaction UUID generated | uuid={}", transactionUuid);

            // eSewa uses NPR directly — NOT paisa like Khalti
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    BigDecimal.valueOf(request.amountNPR()),
                    transactionUuid
            );

            return ResponseEntity.ok(Map.of(
                    "success",         true,
                    "transaction_uuid", transactionUuid,
                    "payload",         payload,
                    "message",         "POST all payload fields as a form to form_action_url"
            ));

        } catch (EsewaException e) {
            log.error("[DEMO] eSewa initiate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }

    /**
     * Step 2 — Handle eSewa callback after payment.
     *
     * <p>eSewa redirects here with: ?data=BASE64_ENCODED_JSON
     *
     * <p>verifyCallback() does three things automatically:
     * <ol>
     *   <li>Decodes the Base64 data parameter</li>
     *   <li>Verifies HMAC-SHA256 signature (tamper protection)</li>
     *   <li>Calls eSewa status API for final confirmation</li>
     * </ol>
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String data) {

        log.info("[DEMO] eSewa callback received | dataLength={}", data.length());

        try {
            EsewaClient.EsewaVerificationResult result =
                    esewaClient.verifyCallback(data);

            if (!result.isPaymentSuccessful()) {
                log.warn("[DEMO] eSewa payment not confirmed | uuid={} | status={}",
                        result.callbackData().transactionUuid(),
                        result.statusResponse().status());

                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "uuid",    result.callbackData().transactionUuid(),
                        "status",  result.statusResponse().status(),
                        "message", "Payment not confirmed"
                ));
            }

            // In a real app:
            // 1. Look up order by transactionUuid from your database
            // 2. Mark order as paid
            // 3. Fulfill the order

            log.info("[DEMO] eSewa payment verified | uuid={} | refId={}",
                    result.callbackData().transactionUuid(),
                    result.statusResponse().refId());

            return ResponseEntity.ok(Map.of(
                    "success",  true,
                    "uuid",     result.callbackData().transactionUuid(),
                    "status",   result.statusResponse().status(),
                    "ref_id",   result.statusResponse().refId(),
                    "message",  "Payment verified — safe to fulfill order"
            ));

        } catch (EsewaException e) {
            log.error("[DEMO] eSewa callback verification failed", e);

            if (e.getMessage().contains("signature verification FAILED")) {
                // Potential tampering — log and alert your team!
                log.error("[DEMO] SECURITY ALERT — eSewa signature mismatch!");
            }

            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error",   e.getMessage()
            ));
        }
    }

    /**
     * Handle eSewa failure redirect.
     * eSewa redirects here when payment is canceled or fails.
     */
    @GetMapping("/failed")
    public ResponseEntity<Map<String, Object>> handleFailure() {
        log.info("[DEMO] eSewa payment failed or canceled by user");
        return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Payment was not completed"
        ));
    }
}