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
 *   <li>{@code refunded=true} is the primary confirmation the refund
 *       was processed. Use this as the single source of truth.</li>
 *   <li>{@code status="Refunded"} — matches
 *       {@link KhaltiPaymentStatus#REFUNDED} when present,
 *       but may be null in some API responses.</li>
 * </ul>
 *
 * @param pidx          Original payment identifier (from initiatePayment)
 * @param transactionId Khalti's internal transaction ID
 * @param refunded      True when refund was successfully processed —
 *                      primary source of truth
 * @param status        Raw status string — may be null in some responses
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
     * {@link KhaltiPaymentStatus#REFUNDED} when the status field is
     * present. Returns {@link KhaltiPaymentStatus#UNKNOWN} if the
     * status field is null — this is safe because
     * {@link #isRefundSuccessful()} uses the {@code refunded}
     * boolean field as the primary source of truth, not this method.
     *
     * @return typed {@link KhaltiPaymentStatus}
     */
    public KhaltiPaymentStatus paymentStatus() {
        return KhaltiPaymentStatus.fromString(this.status);
    }

    /**
     * Returns true only when Khalti confirmed the refund was processed.
     *
     * <p><strong>Fix (Bug #9):</strong> Uses only the {@code refunded}
     * boolean field as the source of truth.
     *
     * <p>The previous implementation also checked
     * {@code paymentStatus().isRefunded()} — this caused a false-negative
     * when Khalti returned {@code refunded=true} alongside a {@code null}
     * or unexpected {@code status} value. In that case, the refund was
     * genuinely confirmed by Khalti but this method returned {@code false},
     * potentially causing double-refunds on retry.
     *
     * <p>Why {@code refunded} boolean is the correct primary source:
     * <ul>
     *   <li>It is a dedicated boolean field — no string parsing needed</li>
     *   <li>It is never ambiguous — either {@code true} or {@code false}</li>
     *   <li>Khalti's own docs state: check the {@code refunded} field
     *       to confirm refund success</li>
     *   <li>The {@code status} string can be null on partial refunds
     *       or during API edge cases</li>
     * </ul>
     *
     * @return true if refund is confirmed — {@code refunded == true}
     */
    public boolean isRefundSuccessful() {
        return Boolean.TRUE.equals(refunded);
    }
}