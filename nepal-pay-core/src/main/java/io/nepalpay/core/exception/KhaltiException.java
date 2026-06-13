package io.nepalpay.core.exception;
/**
 * Exception thrown when Khalti API calls fail.
 *
 * Contains:
 * - httpStatus: The HTTP status code returned by Khalti (0 if no HTTP response)
 * - responseBody: Raw JSON error body from Khalti for debugging
 */
public final class KhaltiException extends NepalPayException {

    private final int httpStatus;
    private final String responseBody;

    public KhaltiException(String message) {
        super(message);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    public KhaltiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus   = 0;
        this.responseBody = null;
    }

    public KhaltiException(String message, int httpStatus, String responseBody) {
        super(message);
        this.httpStatus   = httpStatus;
        this.responseBody = responseBody;
    }

    public int httpStatus()      { return httpStatus;    }
    public String responseBody() { return responseBody;  }

    @Override
    public String toString() {
        return "KhaltiException{message='%s', httpStatus=%d, responseBody='%s'}"
                .formatted(getMessage(), httpStatus, responseBody);
    }
}