package io.nepalpay.core.khalti.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from Khalti payment initiation API.
 *
 * After calling initiatePayment():
 * 1. Store pidx in your database — you need it for verification!
 * 2. Redirect the user to paymentUrl
 * 3. After user completes payment, Khalti redirects to your returnUrl
 * 4. Call lookupPayment(pidx) to verify — NEVER trust the redirect alone
 *
 * @param pidx       Payment identifier — store this in DB immediately!
 * @param paymentUrl Redirect your user to this URL
 * @param expiresAt  ISO-8601 datetime when link expires (60 min in production)
 * @param expiresIn  Seconds until expiry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KhaltiInitiateResponse(

        @JsonProperty("pidx")
        String pidx,

        @JsonProperty("payment_url")
        String paymentUrl,

        @JsonProperty("expires_at")
        String expiresAt,

        @JsonProperty("expires_in")
        Integer expiresIn

) {}