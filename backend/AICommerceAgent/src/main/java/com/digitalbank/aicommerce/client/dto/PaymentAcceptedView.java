package com.digitalbank.aicommerce.client.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The orchestrator's acknowledgement that a payment was accepted for processing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAcceptedView(
        UUID paymentId,
        String state,
        String statusUrl) {
}
