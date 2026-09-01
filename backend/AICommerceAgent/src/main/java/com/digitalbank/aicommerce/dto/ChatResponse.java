package com.digitalbank.aicommerce.dto;

/**
 * The reply of the agent. When a payment is implied, proposal is populated and
 * nothing has been executed yet.
 */
public record ChatResponse(
        String conversationId,
        String reply,
        ProposalSummary proposal) {
}
