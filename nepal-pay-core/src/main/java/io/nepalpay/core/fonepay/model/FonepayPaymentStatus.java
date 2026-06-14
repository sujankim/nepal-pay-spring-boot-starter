package io.nepalpay.core.fonepay.model;

/**
 * Fonepay payment status values from callback response.
 *
 * <p>Source: Fonepay Web Integration Technical Specification
 *
 * <p>RULE: Only {@link #SUCCESS} = payment confirmed.
 * Always verify the HMAC-SHA512 signature FIRST before trusting the status.
 */
public enum FonepayPaymentStatus {

    /** Payment completed successfully */
    SUCCESS,

    /** Payment failed or canceled */
    FAILED,

    /** ⚠️ Unknown status returned by Fonepay */
    UNKNOWN;

    /**
     * Safely parse status string from Fonepay callback.
     * Case-insensitive. Never throws.
     *
     * @param status raw status string from callback
     * @return typed status enum
     */
    public static FonepayPaymentStatus fromString(String status) {
        if (status == null || status.isBlank()) return UNKNOWN;
        return switch (status.trim().toLowerCase()) {
            case "success", "true"  -> SUCCESS;
            case "failed", "false"  -> FAILED;
            default                 -> UNKNOWN;
        };
    }

    /**
     * Returns true only if Fonepay confirmed payment as successful.
     *
     * @return true if payment succeeded
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}