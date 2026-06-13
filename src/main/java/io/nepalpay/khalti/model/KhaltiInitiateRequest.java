package io.nepalpay.khalti.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for Khalti payment initiation.
 * Maps to: POST /epayment/initiate/
 *
 * Source: https://docs.khalti.com/khalti-epayment/
 *
 * Use the builder:
 * <pre>
 * {@code
 * var request = KhaltiInitiateRequest.builder()
 *     .amount(10000L)               // NPR 100 in paisa
 *     .purchaseOrderId("ORD-001")
 *     .purchaseOrderName("Pro Plan")
 *     .build();
 * }
 * </pre>
 *
 * ⚠️ Amount is in PAISA — multiply NPR by 100.
 *    NPR 10 minimum → send 1000 paisa minimum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // Don't send null optional fields to Khalti
public record KhaltiInitiateRequest(

        /**
         * Amount in PAISA (NPR × 100).
         * Example: NPR 100 → 10000 paisa
         */
        @JsonProperty("amount")
        long amount,

        /**
         * Your unique order ID. Must be unique per transaction.
         * Example: "ORD-2024-00123"
         */
        @JsonProperty("purchase_order_id")
        String purchaseOrderId,

        /**
         * Human-readable order name shown to user on Khalti page.
         * Example: "Pro Plan Subscription - 1 Month"
         */
        @JsonProperty("purchase_order_name")
        String purchaseOrderName,

        /**
         * URL Khalti redirects to after payment (success or failure).
         * Auto-filled from nepalpay.khalti.return-url if null.
         */
        @JsonProperty("return_url")
        String returnUrl,

        /**
         * Your merchant website URL.
         * Auto-filled from nepalpay.khalti.website-url if null.
         */
        @JsonProperty("website_url")
        String websiteUrl,

        /**
         * Optional: customer info for better tracking in Khalti dashboard.
         */
        @JsonProperty("customer_info")
        CustomerInfo customerInfo

) {

    /**
     * Customer information shown in Khalti merchant dashboard.
     *
     * @param name  Customer's full name
     * @param email Customer's email address
     * @param phone Customer's phone number
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomerInfo(
            @JsonProperty("name")  String name,
            @JsonProperty("email") String email,
            @JsonProperty("phone") String phone
    ) {}

    // ── Builder ────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long amount;
        private String purchaseOrderId;
        private String purchaseOrderName;
        private String returnUrl;
        private String websiteUrl;
        private CustomerInfo customerInfo;

        private Builder() {}

        public Builder amount(long amount) {
            this.amount = amount;
            return this;
        }

        public Builder purchaseOrderId(String purchaseOrderId) {
            this.purchaseOrderId = purchaseOrderId;
            return this;
        }

        public Builder purchaseOrderName(String purchaseOrderName) {
            this.purchaseOrderName = purchaseOrderName;
            return this;
        }

        public Builder returnUrl(String returnUrl) {
            this.returnUrl = returnUrl;
            return this;
        }

        public Builder websiteUrl(String websiteUrl) {
            this.websiteUrl = websiteUrl;
            return this;
        }

        public Builder customerInfo(String name, String email, String phone) {
            this.customerInfo = new CustomerInfo(name, email, phone);
            return this;
        }

        public KhaltiInitiateRequest build() {
            return new KhaltiInitiateRequest(
                    amount, purchaseOrderId, purchaseOrderName,
                    returnUrl, websiteUrl, customerInfo
            );
        }
    }
}