package com.digitalbank.aicommerce.domain;

/**
 * Lifecycle of a staged payment. A proposal is confirmable exactly once.
 */
public enum ProposalStatus {

    PENDING_CONFIRMATION,
    CONFIRMED,
    EXECUTED,
    REJECTED,
    EXPIRED
}
