package com.digitalbank.aicommerce.service;

import org.springframework.stereotype.Service;

/**
 * Stages, expires and executes payment proposals.
 *
 * <p>Execution calls the existing payment API as the authenticated caller, using
 * the proposal id as the Idempotency-Key so a repeated confirmation returns the
 * original payment instead of creating a second one.</p>
 */
@Service
public class ProposalService {

    // TODO: stage(), confirm(), reject(), expire().
}
