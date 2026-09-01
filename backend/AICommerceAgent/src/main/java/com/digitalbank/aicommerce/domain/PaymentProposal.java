package com.digitalbank.aicommerce.domain;

/**
 * A payment the agent has proposed and a human has not yet confirmed.
 *
 * <p>Persisted so it survives between the proposing request and the confirming
 * request. Execution reads its parameters from this record rather than from
 * anything said at confirmation time.</p>
 */
public class PaymentProposal {

    // TODO: id, customerId, debtorAccountId, billerReferenceNumber,
    // TODO: invoiceReference, amount, currency, status, createdAt, expiresAt,
    // TODO: paymentId once executed. JPA mapping arrives with the schema.
}
