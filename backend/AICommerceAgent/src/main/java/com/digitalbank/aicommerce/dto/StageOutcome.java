package com.digitalbank.aicommerce.dto;

/**
 * The result of trying to stage a payment proposal.
 *
 * <p>A refusal is a value, not an exception, because the model needs to be told
 * why so it can ask the user for what is missing. Only an unauthorized caller
 * ends the turn outright.</p>
 */
public record StageOutcome(
        ProposalSummary proposal,
        String refusalReason) {

    public static StageOutcome staged(ProposalSummary proposal) {
        return new StageOutcome(proposal, null);
    }

    public static StageOutcome refused(String reason) {
        return new StageOutcome(null, reason);
    }

    public boolean isRefused() {
        return refusalReason != null;
    }
}
