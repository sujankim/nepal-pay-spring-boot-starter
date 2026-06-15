package io.nepalpay.core.khalti.model;

/**
 * All possible Khalti payment statuses.
 *
 * <p>Source: https://docs.khalti.com/khalti-epayment/
 *
 * <p>⚠️ RULE: Only COMPLETED = success. Everything else = not paid.
 *
 * <p>After a successful payment is refunded, the status becomes REFUNDED.
 * A refunded payment was previously COMPLETED — it WAS paid, then reversed.
 * Use {@link #isRefunded()} to detect this case separately from failures.
 */
public enum KhaltiPaymentStatus {

    /** ✅ Payment fully completed — ONLY status safe to mark order as paid */
    COMPLETED,

    /**
     * 🔄 Payment was completed and then fully or partially refunded.
     * The original transaction DID succeed — it was later reversed.
     * Check {@link io.nepalpay.core.khalti.model.KhaltiLookupResponse#refunded()}
     * for the boolean flag.
     */
    REFUNDED,

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
     *
     * <p>Case-insensitive. Space-tolerant. Never throws.
     *
     * <p>Known Khalti status strings (from official docs):
     * <ul>
     *   <li>"Completed"   → {@link #COMPLETED}</li>
     *   <li>"Refunded"    → {@link #REFUNDED}</li>
     *   <li>"User canceled" → {@link #USER_CANCELED}</li>
     *   <li>"Canceled"   → {@link #CANCELED}</li>
     *   <li>"Expired"    → {@link #EXPIRED}</li>
     *   <li>"Failed"     → {@link #FAILED}</li>
     *   <li>"Pending"    → {@link #PENDING}</li>
     * </ul>
     *
     * @param status raw status string from Khalti API
     * @return typed status enum, never null
     */
    public static KhaltiPaymentStatus fromString(String status) {
        if (status == null || status.isBlank()) return UNKNOWN;
        return switch (status.trim().toUpperCase().replace(" ", "_")) {
            case "COMPLETED"     -> COMPLETED;
            case "REFUNDED"      -> REFUNDED;
            case "USER_CANCELED" -> USER_CANCELED;
            case "CANCELED"      -> CANCELED;
            case "EXPIRED"       -> EXPIRED;
            case "FAILED"        -> FAILED;
            case "PENDING"       -> PENDING;
            default              -> UNKNOWN;
        };
    }

    /**
     * Returns true only if payment is confirmed successful.
     *
     * <p>A REFUNDED payment was once successful but has since been
     * reversed — this returns false for REFUNDED intentionally.
     * Use {@link #isRefunded()} to detect the refund state.
     *
     * @return true only if status is COMPLETED
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }

    /**
     * Returns true if this payment was refunded.
     *
     * <p>A refunded payment:
     * <ul>
     *   <li>WAS successfully completed at some point</li>
     *   <li>Has since been fully or partially reversed</li>
     *   <li>Should NOT be treated as an active valid payment</li>
     * </ul>
     *
     * @return true if status is REFUNDED
     */
    public boolean isRefunded() {
        return this == REFUNDED;
    }

    /**
     * Returns true if payment definitively failed with no chance of recovery.
     *
     * <p>Safe to stop polling and offer the user a new payment when true.
     * REFUNDED is NOT a terminal failure — it was a successful
     * payment that was later reversed.
     *
     * @return true if CANCELED, USER_CANCELED, EXPIRED, or FAILED
     */
    public boolean isTerminalFailure() {
        return this == CANCELED
                || this == USER_CANCELED
                || this == EXPIRED
                || this == FAILED;
    }
}