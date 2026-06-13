package io.nepalpay.core.connectips.model;

/**
 * ConnectIPS payment validation status values.
 *
 * <p>Source: connectIPS merchant API documentation from NCHL.
 *
 * <p>IMPORTANT: ConnectIPS requires merchant registration with NCHL
 * and a .pfx certificate file to use. Contact NCHL or your bank to
 * obtain merchant credentials before going live.
 */
public enum ConnectIpsPaymentStatus {

    /** Payment verified and successful */
    SUCCESS,

    /** Payment failed or invalid */
    FAILED,

    /** Unknown status returned by API */
    UNKNOWN;

    /**
     * Safely parse status string from ConnectIPS API.
     *
     * @param status raw status string from API
     * @return typed status enum
     */
    public static ConnectIpsPaymentStatus fromString(String status) {
        if (status == null || status.isBlank()) return UNKNOWN;
        return switch (status.trim().toUpperCase()) {
            case "SUCCESS" -> SUCCESS;
            case "FAILED"  -> FAILED;
            default        -> UNKNOWN;
        };
    }

    /**
     * Returns true only if ConnectIPS confirmed the payment as successful.
     *
     * @return true if payment succeeded
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}