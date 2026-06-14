package io.nepalpay.core.exception;

/**
 * Exception thrown when Fonepay operations fail.
 *
 * <p>Covers:
 * <ul>
 *   <li>HMAC-SHA512 signature generation errors</li>
 *   <li>Signature verification failures (security alert!)</li>
 *   <li>Missing or invalid merchant configuration</li>
 *   <li>Invalid callback parameters</li>
 * </ul>
 */
public final class FonepayException extends NepalPayException {

    private final int httpStatus;
    private final String responseBody;

    /**
     * Creates a FonepayException with a message.
     *
     * @param message description of the error
     */
    public FonepayException(String message) {
        super(message);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    /**
     * Creates a FonepayException with a message and cause.
     *
     * @param message description of the error
     * @param cause   the underlying exception
     */
    public FonepayException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    /**
     * Creates a FonepayException with HTTP error details.
     *
     * @param message      description of the error
     * @param httpStatus   HTTP status code
     * @param responseBody raw response body for debugging
     */
    public FonepayException(String message, int httpStatus, String responseBody) {
        super(message);
        this.httpStatus   = httpStatus;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code (0 if not an HTTP error).
     *
     * @return HTTP status code
     */
    public int httpStatus() { return httpStatus; }

    /**
     * Returns the raw response body for debugging.
     *
     * @return response body or null
     */
    public String responseBody() { return responseBody; }
}