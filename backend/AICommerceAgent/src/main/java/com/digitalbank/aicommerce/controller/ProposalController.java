package com.digitalbank.aicommerce.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human confirmation boundary.
 *
 * <p>Confirmation is a request the user makes, not a tool the model can call.
 * The model is not invoked on this path at all: the payment is executed from the
 * stored proposal, so nothing said between proposing and confirming can change
 * the amount, the biller or the account.</p>
 */
@RestController
@RequestMapping("/api/v1/agent/proposals")
public class ProposalController {

    // TODO: POST /{proposalId}/confirm
    // TODO: POST /{proposalId}/reject
}
