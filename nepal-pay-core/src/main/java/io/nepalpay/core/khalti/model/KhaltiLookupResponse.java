package io.nepalpay.core.khalti.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from Khalti payment lookup (verification) API.
 *
 * Call this AFTER receiving the redirect callback.
 * Official rule: Only status "Completed" = successful payment.
 *
 * ⚠️ SECURITY: Always verify totalAmount matches what you initiated.
 *    This prevents amount-tampering attacks.
 *
 * @param pidx            Payment identifier
 * @param status          Raw status string from Khalti API
 * @param totalAmount     Amount in paisa — verify this matches your order!
 * @param transactionId   Khalti's internal transaction ID
 * @param purchaseOrderId Your order ID (echoed back)
 * @param purchaseOrderName Your order name (echoed back)
 * @param fee             Transaction fee in paisa
 * @param refunded        Whether payment has been refunded
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KhaltiLookupResponse(

        @JsonProperty("pidx")
        String pidx,

        @JsonProperty("status")
        String status,

        @JsonProperty("total_amount")
        Long totalAmount,

        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("purchase_order_id")
        String purchaseOrderId,

        @JsonProperty("purchase_order_name")
        String purchaseOrderName,

        @JsonProperty("fee")
        Long fee,

        @JsonProperty("refunded")
        Boolean refunded

) {

    /**
     * Get strongly-typed payment status enum.
     * Safe — handles null/unknown API values gracefully.
     */
    public KhaltiPaymentStatus paymentStatus() {
        return KhaltiPaymentStatus.fromString(this.status);
    }

    /**
     * True ONLY if Khalti confirmed payment as Completed.
     * This is the ONLY check you need before marking an order as paid.
     */
    public boolean isPaymentSuccessful() {
        return paymentStatus().isSuccess();
    }

    /**
     * Verify amount matches expected value (paisa).
     * Call this to prevent amount-tampering:
     * {@code lookup.isAmountValid(expectedAmountPaisa)}
     */
    public boolean isAmountValid(long expectedAmountPaisa) {
        return totalAmount != null && totalAmount.equals(expectedAmountPaisa);
    }

    /**
     * Returns true if this completed payment has been refunded.
     *
     * <p>When {@code true}:
     * <ul>
     *   <li>The payment was once COMPLETED</li>
     *   <li>It has since been reversed (fully or partially)</li>
     *   <li>You should update your order status accordingly</li>
     * </ul>
     *
     * <p>Example usage:
     * <pre>{@code
     * KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);
     * if (lookup.isRefunded()) {
     *     orderService.markRefunded(orderId);
     * }
     * }</pre>
     *
     * @return true if the payment has been refunded
     */
    public boolean isRefunded() {
        return Boolean.TRUE.equals(refunded);
    }
}