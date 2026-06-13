package io.nepalpay.core.esewa.model;

/**
 * eSewa payment status values.
 *
 * Source: https://developer.esewa.com.np/pages/Epay-V2
 *
 * ⚠️ RULE: Only COMPLETE = success. Everything else = not paid.
 * Note: eSewa uses "COMPLETE" (not "COMPLETED" like Khalti)
 */
public enum EsewaPaymentStatus {

    /** ✅ Payment fully completed — ONLY status safe to mark order as paid */
    COMPLETE,

    /** ❌ Payment was not completed */
    INCOMPLETE,

    /** ⚠️ Unknown/unrecognized status — do NOT mark as paid */
    UNKNOWN;

    /**
     * Safely parse status string from eSewa API or callback response.
     * Case-insensitive. Never throws.
     */
    public static EsewaPaymentStatus fromString(String status) {
        if (status == null || status.isBlank()) return UNKNOWN;
        return switch (status.trim().toUpperCase()) {
            case "COMPLETE"   -> COMPLETE;
            case "INCOMPLETE" -> INCOMPLETE;
            default           -> UNKNOWN;
        };
    }

    /** True ONLY if payment is confirmed successful */
    public boolean isSuccess() {
        return this == COMPLETE;
    }
}