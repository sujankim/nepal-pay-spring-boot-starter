package io.nepalpay.connectips.model;

import java.math.BigDecimal;

/**
 * Request for initiating a ConnectIPS payment.
 *
 * <p>Used as input to {@code ConnectIpsClient.buildFormPayload()}.
 * All monetary amounts are in NPR — the client converts to paisa internally.
 *
 * <p>Use the builder:
 * <pre>{@code
 * ConnectIpsPaymentRequest request = ConnectIpsPaymentRequest.builder()
 *     .txnId("TXN-ORD-001")
 *     .txnAmtNPR(100L)
 *     .referenceId("ORD-001")
 *     .remarks("Order payment")
 *     .particulars("NepalPay Demo")
 *     .build();
 * }</pre>
 *
 * @param txnId        Unique transaction ID (alphanumeric and hyphens only)
 * @param txnAmtPaisa  Transaction amount in PAISA (NPR x 100)
 * @param referenceId  Your order or reference ID
 * @param remarks      Optional remarks shown on ConnectIPS gateway
 * @param particulars  Optional particulars field
 */
public record ConnectIpsPaymentRequest(
        String txnId,
        long txnAmtPaisa,
        String referenceId,
        String remarks,
        String particulars
) {

    /**
     * Returns a new builder for {@link ConnectIpsPaymentRequest}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ConnectIpsPaymentRequest}.
     */
    public static final class Builder {

        private String txnId;
        private long txnAmtPaisa;
        private String referenceId;
        private String remarks      = "";
        private String particulars  = "";

        private Builder() {}

        /**
         * Sets the unique transaction ID.
         *
         * @param txnId alphanumeric transaction ID
         * @return this builder
         */
        public Builder txnId(String txnId) {
            this.txnId = txnId;
            return this;
        }

        /**
         * Sets the transaction amount in PAISA (NPR x 100).
         *
         * @param txnAmtPaisa amount in paisa
         * @return this builder
         */
        public Builder txnAmtPaisa(long txnAmtPaisa) {
            this.txnAmtPaisa = txnAmtPaisa;
            return this;
        }

        /**
         * Sets the transaction amount in NPR and converts to paisa.
         *
         * @param amountNPR amount in NPR
         * @return this builder
         */
        public Builder amountNPR(long amountNPR) {
            this.txnAmtPaisa = amountNPR * 100L;
            return this;
        }

        /**
         * Sets the reference ID (your order ID).
         *
         * @param referenceId order or reference ID
         * @return this builder
         */
        public Builder referenceId(String referenceId) {
            this.referenceId = referenceId;
            return this;
        }

        /**
         * Sets optional remarks shown on the ConnectIPS gateway.
         *
         * @param remarks payment remarks
         * @return this builder
         */
        public Builder remarks(String remarks) {
            this.remarks = remarks;
            return this;
        }

        /**
         * Sets optional particulars field.
         *
         * @param particulars payment particulars
         * @return this builder
         */
        public Builder particulars(String particulars) {
            this.particulars = particulars;
            return this;
        }

        /**
         * Builds the {@link ConnectIpsPaymentRequest}.
         *
         * @return new ConnectIpsPaymentRequest instance
         */
        public ConnectIpsPaymentRequest build() {
            return new ConnectIpsPaymentRequest(
                    txnId, txnAmtPaisa, referenceId, remarks, particulars
            );
        }
    }
}