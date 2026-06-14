package io.nepalpay.core.fonepay.model;

/**
 * Signed URL parameters to redirect the user to Fonepay.
 *
 * <p>Fonepay uses a URL redirect model — unlike eSewa which uses form POST.
 * Your backend builds these signed parameters and constructs a redirect URL.
 * The frontend redirects the user to {@link #redirectUrl()}.
 *
 * <p>Flow:
 * <pre>
 * Backend: builds FonepayRedirectParams (HMAC-SHA512 signed)
 *     ↓
 * Backend returns redirectUrl to frontend
 *     ↓
 * Frontend: window.location.href = payload.redirectUrl()
 *     ↓
 * User pays on Fonepay
 *     ↓
 * Fonepay redirects to your returnUrl with callback params
 * </pre>
 *
 * @param pid         Your Fonepay merchant code
 * @param md          Payment mode — always "P"
 * @param prn         Your unique Product Reference Number
 * @param amt         Amount in NPR
 * @param crn         Currency — always "NPR"
 * @param dt          Date in MM/DD/YYYY format
 * @param r1          Remarks 1
 * @param r2          Remarks 2
 * @param dv          HMAC-SHA512 signature (hex lowercase)
 * @param ru          Your return URL
 * @param redirectUrl Full constructed URL to redirect user to
 */
public record FonepayRedirectParams(
        String pid,
        String md,
        String prn,
        String amt,
        String crn,
        String dt,
        String r1,
        String r2,
        String dv,
        String ru,
        String redirectUrl
) {}