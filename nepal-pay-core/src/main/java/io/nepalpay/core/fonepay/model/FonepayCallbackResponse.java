package io.nepalpay.core.fonepay.model;

/**
 * Callback parameters received from Fonepay after payment.
 *
 * <p>Fonepay redirects to your return URL with these as GET query
 * parameters. Example redirect:
 * <pre>
 * GET /api/payment/fonepay/callback
 *     ?PRN=ORD-001-123456
 *     &amp;PID=MERCHANT_CODE
 *     &amp;PS=success
 *     &amp;RC=200
 *     &amp;UID=fonepay-uid
 *     &amp;BC=GBIME
 *     &amp;INI=user@bank
 *     &amp;P_AMT=100.0
 *     &amp;R_AMT=0.0
 *     &amp;DV=HMAC_SHA512_HEX_UPPERCASE
 * </pre>
 *
 * <p><strong>SECURITY — ALWAYS verify the {@code DV} signature
 * before trusting {@code PS}.</strong>
 * Use {@code FonepayClient.verifyCallback()} which performs
 * HMAC-SHA512 signature verification automatically.
 *
 * <p><strong>Why there is no {@code isPaymentSuccessful()} method
 * on this record:</strong>
 * Calling {@code PS} alone to determine payment success is a
 * security vulnerability — any attacker can change {@code PS=failed}
 * to {@code PS=success} in the redirect URL. The only safe way to
 * confirm a Fonepay payment is to verify the {@code DV} HMAC-SHA512
 * signature first via {@code FonepayClient.verifyCallback()}.
 * The result of THAT call exposes {@code isPaymentSuccessful()}.
 *
 * @param prn   Your original Product Reference Number
 * @param pid   Fonepay merchant code
 * @param ps    Payment status — "success" or "failed"
 * @param rc    Response code from Fonepay
 * @param uid   Unique transaction ID from Fonepay
 * @param bc    Bank code of the paying bank
 * @param ini   Transaction initiator identifier
 * @param pAmt  Paid amount
 * @param rAmt  Refund amount
 * @param dv    HMAC-SHA512 signature — MUST be verified before
 *              trusting any other field in this record
 */
public record FonepayCallbackResponse(
        String prn,
        String pid,
        String ps,
        String rc,
        String uid,
        String bc,
        String ini,
        String pAmt,
        String rAmt,
        String dv
) {

    /**
     * Returns the typed payment status parsed from {@code PS}.
     *
     * <p><strong>WARNING: Do NOT use this alone to determine payment
     * success.</strong> The {@code PS} parameter can be forged by an
     * attacker. Always call
     * {@code FonepayClient.verifyCallback(FonepayCallbackResponse)}
     * first — it verifies the HMAC-SHA512 {@code DV} signature and
     * THEN checks this status.
     *
     * <p>This method is intentionally kept for internal use by
     * {@code FonepayClient} — it is called only AFTER signature
     * verification has already passed.
     *
     * @return typed {@link FonepayPaymentStatus} — never null
     */
    public FonepayPaymentStatus paymentStatus() {
        return FonepayPaymentStatus.fromString(this.ps);
    }

    /**
     * Parse from Spring MVC request parameters.
     * Field names match Fonepay's exact callback parameter names.
     *
     * @param prn  PRN parameter
     * @param pid  PID parameter
     * @param ps   PS parameter
     * @param rc   RC parameter
     * @param uid  UID parameter
     * @param bc   BC parameter
     * @param ini  INI parameter
     * @param pAmt P_AMT parameter
     * @param rAmt R_AMT parameter
     * @param dv   DV parameter
     * @return parsed FonepayCallbackResponse
     */
    public static FonepayCallbackResponse of(
            String prn, String pid, String ps, String rc,
            String uid, String bc, String ini,
            String pAmt, String rAmt, String dv) {
        return new FonepayCallbackResponse(
                prn, pid, ps, rc, uid, bc, ini, pAmt, rAmt, dv);
    }
}