package com.digitalbank.aicommerce.client.dto;

import java.util.UUID;

/**
 * The bill payment request sent to the payment orchestrator.
 *
 * <p>Every field is built from a stored {@code PaymentProposal}. Nothing here is
 * ever populated from model output at confirmation time.</p>
 */
public record BillPayCommand(
        UUID debtorAccountId,
        String billerReferenceNumber,
        String invoiceReference,
        /** yyyy-MM-dd, as the orchestrator requires. */
        String executionDate,
        MoneyAmount amount,
        String note) {
}
