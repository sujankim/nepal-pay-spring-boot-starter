package com.example.demo.controller;

import com.example.demo.dto.PaymentInitiateRequest;
import io.nepalpay.core.esewa.model.EsewaFormPayload;
import io.nepalpay.core.exception.EsewaException;
import io.nepalpay.esewa.EsewaClient;
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
 * <h2>Flow</h2>
 * <ol>
 *   <li>POST /api/demo/esewa/initiate → returns signed form payload</li>
 *   <li>Frontend POSTs form fields to payload.form_action_url</li>
 *   <li>User pays on eSewa</li>
 *   <li>eSewa redirects to GET /api/demo/esewa/callback?data=BASE64</li>
 *   <li>We decode + verify HMAC + call status API automatically</li>
 * </ol>
 *
 * <h2>Important: Amount in NPR (not paisa)</h2>
 * eSewa uses NPR directly. NPR 100 = send new BigDecimal("100.00").
 * This is the OPPOSITE of Khalti which uses paisa.
 *
 * <h2>Security Rule</h2>
 * NEVER trust redirect parameters alone.
 * {@code esewaClient.verifyCallback(data)} does three things:
 * 1. Decodes Base64 callback data
 * 2. Verifies HMAC-SHA256 signature (tamper protection)
 * 3. Calls eSewa status API for final confirmation
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/esewa")
@RequiredArgsConstructor
public class EsewaDemoController {

    /**
     * EsewaClient is auto-injected by NepalPay Spring Boot Starter.
     * No @Bean method, no config class — just inject and use.
     */
    private final EsewaClient esewaClient;

    /**
     * Build a signed eSewa form payload and return it to the frontend.
     *
     * <p>Example request:
     * <pre>
     * POST /api/demo/esewa/initiate
     * Content-Type: application/json
     *
     * {
     *   "orderId": "ORD-001",
     *   "amountNPR": 100,
     *   "productName": "Pro Plan"
     * }
     * </pre>
     *
     * <p>The frontend must POST all payload fields to
     * {@code payload.form_action_url} as an HTML form.
     *
     * @param request payment details
     * @return signed eSewa form payload
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        log.info("[DEMO] eSewa initiate | orderId={} | amountNPR={}",
                request.orderId(), request.amountNPR());

        try {
            // Generate UUID — store this in your DB before returning!
            String transactionUuid = EsewaClient.generateTransactionUuid();

            // ⚠️ eSewa uses NPR directly — NOT paisa like Khalti
            EsewaFormPayload payload = esewaClient.buildFormPayload(
                    BigDecimal.valueOf(request.amountNPR()),
                    transactionUuid
            );

            log.info("[DEMO] eSewa payload built | uuid={}", transactionUuid);

            return ResponseEntity.ok(Map.of(
                    "transaction_uuid", transactionUuid,
                    "payload",          payload,
                    "message",          "POST all payload fields as a form to form_action_url"
            ));

        } catch (EsewaException e) {
            log.error("[DEMO] eSewa initiate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Handle eSewa callback after payment.
     *
     * <p>eSewa redirects here with a Base64-encoded JSON data parameter.
     * {@code verifyCallback(data)} automatically:
     * <ol>
     *   <li>Decodes the Base64 data</li>
     *   <li>Verifies the HMAC-SHA256 signature</li>
     *   <li>Calls eSewa status API for final confirmation</li>
     * </ol>
     *
     * <p>Example redirect:
     * <pre>
     * GET /api/demo/esewa/callback?data=BASE64_ENCODED_JSON
     * </pre>
     *
     * @param data Base64-encoded JSON from eSewa redirect
     * @return verification result
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
                        "verified", false,
                        "uuid",     result.callbackData().transactionUuid(),
                        "status",   result.statusResponse().status(),
                        "message",  "Payment not confirmed"
                ));
            }

            // In a real app:
            // 1. Load order from DB by transactionUuid
            // 2. Mark order as paid
            // 3. Redirect user to success page

            log.info("[DEMO] eSewa payment verified | uuid={} | refId={}",
                    result.callbackData().transactionUuid(),
                    result.statusResponse().refId());

            return ResponseEntity.ok(Map.of(
                    "verified", true,
                    "uuid",     result.callbackData().transactionUuid(),
                    "status",   result.statusResponse().status(),
                    "ref_id",   result.statusResponse().refId() != null
                            ? result.statusResponse().refId() : "",
                    "message",  "Payment verified — safe to fulfil order"
            ));

        } catch (EsewaException e) {
            log.error("[DEMO] eSewa callback error", e);

            if (e.getMessage().contains("signature verification FAILED")) {
                log.error("[DEMO] SECURITY ALERT — eSewa signature mismatch!");
            }

            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Handle eSewa failure redirect.
     * eSewa redirects here when payment is canceled or fails.
     *
     * @return failure message
     */
    @GetMapping("/failed")
    public ResponseEntity<Map<String, String>> handleFailure() {
        log.info("[DEMO] eSewa payment failed or canceled");
        return ResponseEntity.ok(Map.of(
                "verified", "false",
                "message",  "Payment was canceled or failed"
        ));
    }
}