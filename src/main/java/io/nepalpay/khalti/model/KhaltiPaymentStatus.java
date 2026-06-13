package io.nepalpay.khalti.model;

/**
 * All possible Khalti payment statuses.
 *
 * Source: https://docs.khalti.com/khalti-epayment/
 *
 * ⚠️ RULE: Only COMPLETED = success. Everything else = not paid.
 */
public enum KhaltiPaymentStatus {

    /** ✅ Payment fully completed — ONLY status safe to mark order as paid */
    COMPLETED,

    /** ❌ User actively canceled the payment */
    USER_CANCELED,

    /** ❌ Payment was canceled */
    CANCELED,

    /** ❌ Payment link expired (60 min limit in production) */
    EXPIRED,

    /** ❌ Payment failed */
    FAILED,

    /** ⏳ Initiated but not yet completed — poll again */
    PENDING,

    /** ⚠️ Unknown/unrecognized status — do NOT mark as paid, contact Khalti */
    UNKNOWN;

    /**
     * Safely parse status string from Khalti API response.
     * Case-insensitive. Space-tolerant. Never throws.
     */
    public static KhaltiPaymentStatus fromString(String status) {
        if (status == null || status.isBlank()) return UNKNOWN;
        return switch (status.trim().toUpperCase().replace(" ", "_")) {
            case "COMPLETED"     -> COMPLETED;
            case "USER_CANCELED" -> USER_CANCELED;
            case "CANCELED"      -> CANCELED;
            case "EXPIRED"       -> EXPIRED;
            case "FAILED"        -> FAILED;
            case "PENDING"       -> PENDING;
            default              -> UNKNOWN;
        };
    }

    /** True ONLY if payment is confirmed successful */
    public boolean isSuccess() {
        return this == COMPLETED;
    }

    /** True if payment definitively failed (safe to stop retrying) */
    public boolean isTerminalFailure() {
        return this == CANCELED
                || this == USER_CANCELED
                || this == EXPIRED
                || this == FAILED;
    }
}