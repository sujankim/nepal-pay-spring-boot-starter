package io.nepalpay.esewa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from eSewa transaction status API.
 *
 * Called server-side after receiving and signature-verifying the callback.
 * This is the final confirmation that the payment actually happened.
 *
 * API (Sandbox):
 *   GET https://rc.esewa.com.np/api/epay/transaction/status/
 *       ?product_code=EPAYTEST&total_amount=100&transaction_uuid=123
 *
 * API (Production):
 *   GET https://esewa.com.np/api/epay/transaction/status/
 *       ?product_code=EPAYTEST&total_amount=100&transaction_uuid=123
 *
 * Example response from official eSewa docs:
 * {
 *   "product_code": "EPAYTEST",
 *   "transaction_uuid": "123",
 *   "total_amount": 100.0,
 *   "status": "COMPLETE",
 *   "ref_id": "0001TS9"
 * }
 *
 * @param productCode     Your merchant/product code
 * @param transactionUuid Your original transaction UUID
 * @param totalAmount     Total amount paid
 * @param status          Payment status — only "COMPLETE" = success
 * @param refId           eSewa's internal reference ID
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
     * Get typed payment status.
     */
    public EsewaPaymentStatus paymentStatus() {
        return EsewaPaymentStatus.fromString(this.status);
    }

    /**
     * True ONLY if eSewa status API confirmed payment as COMPLETE.
     */
    public boolean isPaymentSuccessful() {
        return paymentStatus().isSuccess();
    }
}