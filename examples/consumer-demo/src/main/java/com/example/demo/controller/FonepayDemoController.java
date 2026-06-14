package com.example.demo.controller;

import io.nepalpay.core.exception.FonepayException;
import io.nepalpay.core.fonepay.model.FonepayCallbackResponse;
import io.nepalpay.core.fonepay.model.FonepayPaymentRequest;
import io.nepalpay.core.fonepay.model.FonepayRedirectParams;
import io.nepalpay.fonepay.FonepayClient;
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
 * Demo controller showing Fonepay web payment integration.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>POST /api/demo/fonepay/initiate → returns redirectUrl</li>
 *   <li>Frontend: window.location.href = redirectUrl</li>
 *   <li>User pays on Fonepay gateway</li>
 *   <li>Fonepay redirects to GET /api/demo/fonepay/callback with params</li>
 *   <li>We verify HMAC-SHA512 signature + check status</li>
 * </ol>
 *
 * <h2>Amount in NPR (not paisa)</h2>
 * Fonepay uses NPR directly as a double. NPR 100 → send 100.0.
 *
 * <h2>Security Rule</h2>
 * ALWAYS call {@code fonepayClient.verifyCallback()} server-side.
 * The redirect parameters can be faked. The HMAC-SHA512 signature cannot.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/fonepay")
@RequiredArgsConstructor
public class FonepayDemoController {

    /**
     * FonepayClient is auto-injected by NepalPay when configured.
     */
    private final FonepayClient fonepayClient;

    /**
     * Build signed Fonepay redirect parameters.
     *
     * <p>Example request:
     * <pre>
     * POST /api/demo/fonepay/initiate
     * {"orderId": "ORD-001", "amountNPR": 100, "productName": "Pro Plan"}
     * </pre>
     *
     * @param request payment details
     * @return redirect URL to send user to Fonepay
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestBody Map<String, Object> request) {

        String orderId    = (String) request.get("orderId");
        double amountNPR  = Double.parseDouble(request.get("amountNPR").toString());
        String productName = (String) request.getOrDefault("productName", "Payment");

        // PRN must be 3-25 characters — truncate if needed
        String prn = ("FP-" + orderId).substring(0, Math.min(25, "FP-" + orderId + "-" + System.currentTimeMillis()).length());

        log.info("[DEMO] Fonepay initiate | orderId={} | amountNPR={}", orderId, amountNPR);

        try {
            // In a real app: save prn to your DB before returning!
            FonepayRedirectParams params = fonepayClient.buildRedirectParams(
                    FonepayPaymentRequest.builder()
                            .prn(prn)
                            .amount(amountNPR)    // NPR directly — not paisa!
                            .remarks1(productName)
                            .remarks2("NepalPay Demo")
                            .build()
            );

            log.info("[DEMO] Fonepay redirect built | prn={}", prn);

            return ResponseEntity.ok(Map.of(
                    "prn",          prn,
                    "redirect_url", params.redirectUrl(),
                    "message",      "Redirect user to redirect_url"
            ));

        } catch (FonepayException e) {
            log.error("[DEMO] Fonepay initiate failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Handle Fonepay callback after payment.
     *
     * <p>Fonepay redirects here with all payment params as GET query params.
     * We ALWAYS verify the HMAC-SHA512 signature before trusting the status.
     *
     * @param prn   original PRN from initiate
     * @param pid   merchant code
     * @param ps    payment status (success/failed)
     * @param rc    response code
     * @param uid   Fonepay unique ID
     * @param bc    bank code
     * @param ini   initiator
     * @param pAmt  paid amount
     * @param rAmt  refund amount
     * @param dv    HMAC-SHA512 signature for verification
     * @return verification result
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam String prn,
            @RequestParam String pid,
            @RequestParam String ps,
            @RequestParam String rc,
            @RequestParam String uid,
            @RequestParam String bc,
            @RequestParam String ini,
            @RequestParam(name = "P_AMT") String pAmt,
            @RequestParam(name = "R_AMT") String rAmt,
            @RequestParam(name = "DV")    String dv) {

        log.info("[DEMO] Fonepay callback | prn={} | ps={}", prn, ps);

        try {
            FonepayCallbackResponse callback =
                    FonepayCallbackResponse.of(prn, pid, ps, rc, uid, bc, ini, pAmt, rAmt, dv);

            // ✅ ALWAYS verify signature first — redirect params can be faked!
            FonepayClient.FonepayVerificationResult result =
                    fonepayClient.verifyCallback(callback);

            if (!result.isPaymentSuccessful()) {
                log.warn("[DEMO] Fonepay payment not successful | prn={} | ps={}", prn, ps);
                return ResponseEntity.ok(Map.of(
                        "verified", false,
                        "prn",      prn,
                        "status",   ps,
                        "message",  "Payment not successful"
                ));
            }

            // In a real app:
            // 1. Load order from DB by prn
            // 2. Mark order as paid
            log.info("[DEMO] Fonepay payment verified | prn={} | uid={}", prn, uid);

            return ResponseEntity.ok(Map.of(
                    "verified", true,
                    "prn",      prn,
                    "uid",      uid,
                    "pAmt",     pAmt,
                    "message",  "Payment verified — safe to fulfil order"
            ));

        } catch (FonepayException e) {
            log.error("[DEMO] Fonepay callback error | prn={}", prn, e);

            if (e.getMessage().contains("signature verification FAILED")) {
                log.error("[DEMO] SECURITY ALERT — Fonepay signature mismatch! prn={}", prn);
            }

            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }
}