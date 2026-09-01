package com.digitalbank.aicommerce.tool;

import org.springframework.stereotype.Component;

/**
 * Stages a bill payment for human confirmation.
 *
 * <p>This tool does not call the payment API. It validates the biller and the
 * account on the server, writes a proposal and returns a summary. Execution
 * happens only when the user confirms, on a path the model is not part of.</p>
 */
@Component
public class ProposeBillPaymentTool implements AgentTool {

    @Override
    public String name() {
        return "propose_bill_payment";
    }

    // TODO: validate, stage a PaymentProposal, return the summary.
}
