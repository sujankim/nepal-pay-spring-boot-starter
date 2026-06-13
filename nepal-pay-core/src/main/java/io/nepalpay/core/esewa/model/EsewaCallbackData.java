package io.nepalpay.core.esewa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Decoded callback data from eSewa after payment.
 *
 * When payment completes, eSewa redirects to your successUrl with:
 *   GET /your-success-url?data=BASE64_ENCODED_JSON
 *
 * Your backend must:
 * 1. Decode the base64 "data" parameter → this record
 * 2. Re-compute HMAC signature and compare (prevents tampering)
 * 3. Call status API for final confirmation
 *
 * This is handled automatically by EsewaClient.verifyCallback(encodedData)
 *
 * Example decoded JSON from official eSewa docs:
 * {
 *   "transaction_code": "000AWEO",
 *   "status": "COMPLETE",
 *   "total_amount": "1000.0",
 *   "transaction_uuid": "250610-162413",
 *   "product_code": "EPAYTEST",
 *   "signed_field_names": "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names",
 *   "signature": "62GcfZTmVkzhtUeh+QJ1AqiJrjoWWGof3U+eTPTZ7fA="
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EsewaCallbackData(

        @JsonProperty("transaction_code")
        String transactionCode,

        @JsonProperty("status")
        String status,

        @JsonProperty("total_amount")
        String totalAmount,

        @JsonProperty("transaction_uuid")
        String transactionUuid,

        @JsonProperty("product_code")
        String productCode,

        @JsonProperty("signed_field_names")
        String signedFieldNames,

        @JsonProperty("signature")
        String signature

) {

    /**
     * Get typed payment status.
     * eSewa uses "COMPLETE" (not "COMPLETED").
     */
    public EsewaPaymentStatus paymentStatus() {
        return EsewaPaymentStatus.fromString(this.status);
    }
}