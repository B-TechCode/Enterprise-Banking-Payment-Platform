package com.digitalbank.aicommerce.dto;

import java.util.UUID;

/**
 * What the confirmation endpoint returns once the payment has been accepted by
 * the orchestrator.
 */
public record PaymentConfirmationResponse(
        UUID proposalId,
        UUID paymentId,
        String proposalStatus,
        String paymentState,
        String statusUrl) {
}
