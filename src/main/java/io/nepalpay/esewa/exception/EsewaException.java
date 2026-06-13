package io.nepalpay.esewa.exception;

import io.nepalpay.exception.NepalPayException;

/**
 * Exception thrown when eSewa operations fail.
 *
 * Covers:
 * - Signature generation errors
 * - Base64 decode failures
 * - Signature verification mismatches (SECURITY ALERT)
 * - Status API call failures
 */
public final class EsewaException extends NepalPayException {

    private final int httpStatus;
    private final String responseBody;

    public EsewaException(String message) {
        super(message);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    public EsewaException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    public EsewaException(String message, int httpStatus, String responseBody) {
        super(message);
        this.httpStatus   = httpStatus;
        this.responseBody = responseBody;
    }

    public int httpStatus()      { return httpStatus;   }
    public String responseBody() { return responseBody; }

    @Override
    public String toString() {
        return "EsewaException{message='%s', httpStatus=%d, responseBody='%s'}"
                .formatted(getMessage(), httpStatus, responseBody);
    }
}