package io.nepalpay.core.connectips.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for ConnectIPS transaction validation API.
 *
 * <p>Called server-side to verify a payment after user completes
 * the transaction on the ConnectIPS gateway.
 *
 * <p>Validation endpoint (UAT):
 * POST https://uat.connectips.com/connectipswebws/api/creditor/validatetxn
 *
 * <p>The API uses HTTP Basic Authentication:
 * username = appId, password = appPassword
 *
 * @param merchantId  NCHL-assigned merchant ID
 * @param appId       Application ID
 * @param referenceId Your original order/reference ID
 * @param txnAmt      Transaction amount in paisa (must match original)
 * @param token       RSA-SHA256 signed token for validation
 */
public record ConnectIpsValidateRequest(

        @JsonProperty("merchantId")
        int merchantId,

        @JsonProperty("appId")
        String appId,

        @JsonProperty("referenceId")
        String referenceId,

        @JsonProperty("txnAmt")
        long txnAmt,

        @JsonProperty("token")
        String token

) {}