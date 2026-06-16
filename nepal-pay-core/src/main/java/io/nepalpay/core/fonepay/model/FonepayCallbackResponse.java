package io.nepalpay.core.fonepay.model;

/**
 * Callback parameters received from Fonepay after payment.
 *
 * <p>Fonepay redirects to your return URL with these as GET query parameters.
 * Example redirect:
 * <pre>
 * GET /api/payment/fonepay/callback
 *     ?PRN=ORD-001-123456
 *     &PID=MERCHANT_CODE
 *     &PS=success
 *     &RC=200
 *     &UID=fonepay-uid
 *     &BC=GBIME
 *     &INI=user@bank
 *     &P_AMT=100.0
 *     &R_AMT=0.0
 *     &DV=HMAC_SHA512_HEX_UPPERCASE
 * </pre>
 *
 * <p>ALWAYS verify the {@code DV} signature before trusting {@code PS}.
 * Use {@code FonepayClient.verifyCallback()} which
 * does signature verification automatically.
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
 * @param dv    HMAC-SHA512 signature for verification (hex, uppercase)
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
     * Returns the typed payment status.
     *
     * @return typed {@link FonepayPaymentStatus}
     */
    public FonepayPaymentStatus paymentStatus() {
        return FonepayPaymentStatus.fromString(this.ps);
    }

    /**
     * Returns true only if Fonepay reported payment as successful.
     * NOTE: Always call
     * {@link io.nepalpay.fonepay.FonepayClient#verifyCallback} instead —
     * it verifies the HMAC signature BEFORE checking this status.
     *
     * @return true if payment status is success
     */
    public boolean isPaymentSuccessful() {
        return paymentStatus().isSuccess();
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