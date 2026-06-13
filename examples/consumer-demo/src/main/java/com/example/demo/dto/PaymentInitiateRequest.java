package com.example.demo.dto;

/**
 * Simple payment initiation request.
 *
 * @param orderId     Your unique order identifier
 * @param amountNPR   Amount in NPR (for Khalti: will be converted to paisa)
 * @param productName Human-readable product name shown to user
 */
public record PaymentInitiateRequest(
        String orderId,
        long amountNPR,
        String productName
) {}