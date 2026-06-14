package io.nepalpay.core.fonepay.model;

/**
 * Request for initiating a Fonepay web payment.
 *
 * <p>Use the builder:
 * <pre>{@code
 * FonepayPaymentRequest request = FonepayPaymentRequest.builder()
 *     .prn("ORD-001-" + System.currentTimeMillis())
 *     .amount(100.0)
 *     .remarks1("Payment for Pro Plan")
 *     .remarks2("NepalPay Demo")
 *     .build();
 * }</pre>
 *
 * <p><strong>Amount:</strong> Fonepay uses NPR directly as a Double.
 * NPR 100 → send {@code 100.0}. Neither paisa nor BigDecimal.
 *
 * @param prn      Product Reference Number — your unique transaction ID (3-25 chars)
 * @param amount   Payment amount in NPR as a double
 * @param remarks1 Payment details shown to user (max 160 chars)
 * @param remarks2 Optional additional remarks (max 50 chars)
 */
public record FonepayPaymentRequest(
        String prn,
        double amount,
        String remarks1,
        String remarks2
) {

    /**
     * Returns a new builder for {@link FonepayPaymentRequest}.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link FonepayPaymentRequest}.
     */
    public static final class Builder {

        private String prn;
        private double amount;
        private String remarks1 = "Payment";
        private String remarks2 = "";

        private Builder() {}

        /**
         * Sets the Product Reference Number.
         * Must be unique per transaction. Between 3 and 25 characters.
         *
         * @param prn unique transaction reference
         * @return this builder
         */
        public Builder prn(String prn) {
            this.prn = prn;
            return this;
        }

        /**
         * Sets the payment amount in NPR.
         * Fonepay uses NPR directly as a double — not paisa.
         *
         * @param amount amount in NPR (e.g. 100.0 for NPR 100)
         * @return this builder
         */
        public Builder amount(double amount) {
            this.amount = amount;
            return this;
        }

        /**
         * Sets remarks1 — payment details shown to the user.
         * Maximum 160 characters.
         *
         * @param remarks1 payment description
         * @return this builder
         */
        public Builder remarks1(String remarks1) {
            this.remarks1 = remarks1;
            return this;
        }

        /**
         * Sets remarks2 — optional additional remarks.
         * Maximum 50 characters.
         *
         * @param remarks2 additional remarks
         * @return this builder
         */
        public Builder remarks2(String remarks2) {
            this.remarks2 = remarks2;
            return this;
        }

        /**
         * Builds the {@link FonepayPaymentRequest}.
         *
         * @return new FonepayPaymentRequest
         */
        public FonepayPaymentRequest build() {
            return new FonepayPaymentRequest(prn, amount, remarks1, remarks2);
        }
    }
}