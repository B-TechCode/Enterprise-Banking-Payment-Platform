package com.digitalbank.aicommerce.tool;

/**
 * Stages a bill payment for human confirmation.
 *
 * <p>This tool does not call the payment API. It validates the biller and the
 * account on the server, writes a proposal and returns a summary. Execution
 * happens only when the user confirms, on a path the model is not part of.</p>
 *
 * <p>Not yet a capability. It deliberately does not implement {@link AgentTool}
 * and is not a Spring bean, so there is no path by which this slice could offer
 * it to the model: the agent can currently read accounts and nothing else. It
 * becomes a registered tool in the payment slice, which is also when it must be
 * added to the allowlist in {@link ToolRegistry}.</p>
 */
public class ProposeBillPaymentTool {

    public String name() {
        return "propose_bill_payment";
    }

    // TODO: validate, stage a PaymentProposal, return the summary.
}
