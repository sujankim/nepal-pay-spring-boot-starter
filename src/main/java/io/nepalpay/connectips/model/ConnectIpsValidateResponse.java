package io.nepalpay.connectips.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from ConnectIPS transaction validation API.
 *
 * <p>Example successful response:
 * <pre>
 * {
 *   "merchantId": 550,
 *   "appId": "MER-550-APP-1",
 *   "referenceId": "txn-123",
 *   "txnAmt": "500",
 *   "status": "SUCCESS",
 *   "statusDesc": "TRANSACTION SUCCESSFUL"
 * }
 * </pre>
 *
 * @param merchantId  Your merchant ID
 * @param appId       Your application ID
 * @param referenceId Your original reference ID
 * @param txnAmt      Amount that was transacted
 * @param status      Transaction status — SUCCESS or FAILED
 * @param statusDesc  Human-readable status description
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectIpsValidateResponse(

        @JsonProperty("merchantId")
        Integer merchantId,

        @JsonProperty("appId")
        String appId,

        @JsonProperty("referenceId")
        String referenceId,

        @JsonProperty("txnAmt")
        String txnAmt,

        @JsonProperty("status")
        String status,

        @JsonProperty("statusDesc")
        String statusDesc

) {

    /**
     * Returns the typed payment status enum.
     *
     * @return typed ConnectIPS payment status
     */
    public ConnectIpsPaymentStatus paymentStatus() {
        return ConnectIpsPaymentStatus.fromString(this.status);
    }

    /**
     * Returns true only if ConnectIPS confirmed transaction as SUCCESS.
     *
     * @return true if payment is confirmed successful
     */
    public boolean isPaymentSuccessful() {
        return paymentStatus().isSuccess();
    }
}