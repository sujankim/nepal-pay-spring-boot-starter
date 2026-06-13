package io.nepalpay.exception;

/**
 * Base exception for all NepalPay errors.
 * All gateway-specific exceptions extend this.
 *
 * Catch this if you want to handle ALL nepal pay errors in one place:
 * {@code
 * try {
 *     khaltiClient.initiatePayment(...);
 * } catch (NepalPayException e) {
 *     // handles KhaltiException, EsewaException, etc.
 * }
 * }
 */
public class NepalPayException extends RuntimeException {

    public NepalPayException(String message) {
        super(message);
    }

    public NepalPayException(String message, Throwable cause) {
        super(message, cause);
    }
}