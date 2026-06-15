package io.nepalpay.core.khalti.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from Khalti refund API.
 *
 * <p>Returned after calling:
 * {@code POST /api/merchant-transaction/{transaction_id}/refund/}
 *
 * <p>Example successful response:
 * <pre>
 * {
 *   "pidx":           "HT6o6PEZRWFJ5ygavzHWd5",
 *   "transaction_id": "GFq9PFS7b2iYvL8Lir9oXe",
 *   "refunded":       true,
 *   "status":         "Refunded"
 * }
 * </pre>
 *
 * <p><strong>Remember:</strong>
 * <ul>
 *   <li>{@code pidx} — the original payment identifier from
 *       {@link KhaltiInitiateResponse}</li>
 *   <li>{@code transactionId} — Khalti's internal transaction ID.
 *       This is the same value you passed INTO the refund call.</li>
 *   <li>{@code refunded=true} confirms the refund was processed.</li>
 *   <li>{@code status="Refunded"} — matches
 *       {@link KhaltiPaymentStatus#REFUNDED}</li>
 * </ul>
 *
 * @param pidx          Original payment identifier (from initiatePayment)
 * @param transactionId Khalti's internal transaction ID
 * @param refunded      True when refund was successfully processed
 * @param status        Raw status string — should be "Refunded" on success
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KhaltiRefundResponse(

        @JsonProperty("pidx")
        String pidx,

        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("refunded")
        Boolean refunded,

        @JsonProperty("status")
        String status

) {

    /**
     * Returns the typed payment status enum.
     *
     * <p>On a successful refund this returns
     * {@link KhaltiPaymentStatus#REFUNDED}.
     *
     * @return typed {@link KhaltiPaymentStatus}
     */
    public KhaltiPaymentStatus paymentStatus() {
        return KhaltiPaymentStatus.fromString(this.status);
    }

    /**
     * Returns true only when Khalti confirmed the refund was processed.
     *
     * <p>Checks BOTH the {@code refunded} boolean field AND the
     * {@code status} field. Both must agree for this to return true.
     * This double-check protects against partial/unexpected responses.
     *
     * @return true if refund is confirmed successful
     */
    public boolean isRefundSuccessful() {
        return Boolean.TRUE.equals(refunded)
                && paymentStatus().isRefunded();
    }
}