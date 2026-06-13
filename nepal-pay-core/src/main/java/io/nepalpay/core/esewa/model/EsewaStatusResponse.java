package io.nepalpay.core.esewa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from eSewa transaction status API.
 *
 * <p>Called server-side after receiving and signature-verifying the callback.
 * This is the final confirmation that the payment actually happened.
 *
 * <p>Sandbox API:
 * <pre>
 * GET https://rc.esewa.com.np/api/epay/transaction/status/
 *     ?product_code=EPAYTEST&amp;total_amount=100&amp;transaction_uuid=123
 * </pre>
 *
 * <p>Production API:
 * <pre>
 * GET https://esewa.com.np/api/epay/transaction/status/
 *     ?product_code=EPAYTEST&amp;total_amount=100&amp;transaction_uuid=123
 * </pre>
 *
 * <p>Example response:
 * <pre>
 * {
 *   "product_code": "EPAYTEST",
 *   "transaction_uuid": "123",
 *   "total_amount": 100.0,
 *   "status": "COMPLETE",
 *   "ref_id": "0001TS9"
 * }
 * </pre>
 *
 * @param productCode     Your merchant/product code
 * @param transactionUuid Your original transaction UUID
 * @param totalAmount     Total amount paid
 * @param status          Payment status — only "COMPLETE" means success
 * @param refId           eSewa internal reference ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EsewaStatusResponse(

        @JsonProperty("product_code")
        String productCode,

        @JsonProperty("transaction_uuid")
        String transactionUuid,

        @JsonProperty("total_amount")
        Double totalAmount,

        @JsonProperty("status")
        String status,

        @JsonProperty("ref_id")
        String refId

) {

    /**
     * Returns the typed payment status enum.
     *
     * @return typed {@link EsewaPaymentStatus}
     */
    public EsewaPaymentStatus paymentStatus() {
        return EsewaPaymentStatus.fromString(this.status);
    }

    /**
     * Returns true only if eSewa confirmed payment as COMPLETE.
     *
     * @return true if payment is successful
     */
    public boolean isPaymentSuccessful() {
        return paymentStatus().isSuccess();
    }
}