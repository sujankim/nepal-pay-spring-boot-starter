package io.nepalpay.connectips.exception;

import io.nepalpay.exception.NepalPayException;

/**
 * Exception thrown when ConnectIPS operations fail.
 *
 * <p>Covers:
 * <ul>
 *   <li>RSA signature generation errors (missing or invalid .pfx)</li>
 *   <li>Transaction validation API failures</li>
 *   <li>Missing or invalid merchant credentials</li>
 * </ul>
 */
public final class ConnectIpsException extends NepalPayException {

    private final int httpStatus;
    private final String responseBody;

    /**
     * Creates a ConnectIPS exception with a message.
     *
     * @param message description of the error
     */
    public ConnectIpsException(String message) {
        super(message);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    /**
     * Creates a ConnectIPS exception with a message and cause.
     *
     * @param message description of the error
     * @param cause   the underlying exception
     */
    public ConnectIpsException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    /**
     * Creates a ConnectIPS exception with HTTP error details.
     *
     * @param message      description of the error
     * @param httpStatus   HTTP status code from ConnectIPS API
     * @param responseBody raw response body for debugging
     */
    public ConnectIpsException(String message, int httpStatus, String responseBody) {
        super(message);
        this.httpStatus   = httpStatus;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code from ConnectIPS API (0 if not an HTTP error).
     *
     * @return HTTP status code
     */
    public int httpStatus() { return httpStatus; }

    /**
     * Returns the raw response body from ConnectIPS API for debugging.
     *
     * @return response body string or null
     */
    public String responseBody() { return responseBody; }
}