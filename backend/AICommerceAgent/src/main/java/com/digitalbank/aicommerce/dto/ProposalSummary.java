package com.digitalbank.aicommerce.dto;

import java.math.BigDecimal;

/**
 * What the user is asked to confirm. Deliberately explicit about amount, payee
 * and source account, so the confirmation is meaningful rather than a formality.
 */
public record ProposalSummary(
        String proposalId,
        String billerName,
        String maskedAccountNumber,
        BigDecimal amount,
        String currency,
        String expiresAt) {
}
