package com.digitalbank.aicommerce.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.digitalbank.aicommerce.dto.ProposalSummary;
import com.digitalbank.aicommerce.dto.StageOutcome;
import com.digitalbank.aicommerce.service.ProposalService;

import lombok.RequiredArgsConstructor;

/**
 * Stages a bill payment for human confirmation.
 *
 * <p>This tool does not call the payment API. It validates the biller and the
 * account on the server, writes a proposal and returns a summary. Execution
 * happens only when the user confirms, on a path the model is not part of.</p>
 *
 * <p>It reaches {@code ProposalService.stage} only. The payment client lives
 * behind {@code confirm}, which nothing here can call, so there is no sequence
 * of tool calls that results in money moving.</p>
 *
 * <p>Every parameter is gathered in a single turn: the model calls
 * {@code get_my_accounts} and {@code get_my_billers} first and passes exact
 * identifiers from their results. Nothing depends on remembering an earlier
 * message, because there is no memory between requests. Anything missing is a
 * refusal, recorded as DENIED, with a reason telling the model to ask the user
 * rather than fill the gap itself.</p>
 */
@Component
@RequiredArgsConstructor
public class ProposeBillPaymentTool implements AgentTool {

    private final ProposalService proposalService;

    @Override
    public String name() {
        return "propose_bill_payment";
    }

    @Override
    public String description() {
        return "Prepares a bill payment for the user to confirm. This does NOT pay "
                + "anything: it only stages a proposal that the user must separately "
                + "approve before any money moves. Call get_my_accounts and "
                + "get_my_billers first so you can pass exact identifiers. If the user "
                + "has not told you the amount or the bill/invoice number, ask them "
                + "instead of calling this tool.";
    }

    @Override
    public Map<String, Object> inputSchema() {

        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("debtorAccountId", Map.of(
                "type", "STRING",
                "description", "The accountId of the account to pay from, exactly as "
                        + "returned by get_my_accounts. Never invent this value."));

        properties.put("billerReferenceNumber", Map.of(
                "type", "STRING",
                "description", "The referenceNumber of the biller, exactly as returned "
                        + "by get_my_billers. Never invent or guess this value."));

        properties.put("amount", Map.of(
                "type", "STRING",
                "description", "The amount to pay, as a decimal string with at most two "
                        + "decimal places, for example \"75.00\". Use only an amount the "
                        + "user stated explicitly."));

        properties.put("invoiceReference", Map.of(
                "type", "STRING",
                "description", "The bill or invoice number the user gave for this "
                        + "payment. If the user has not provided one, do not call this "
                        + "tool - ask them for it."));

        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", java.util.List.of(
                        "debtorAccountId", "billerReferenceNumber", "amount", "invoiceReference"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, String conversationId) {

        StageOutcome outcome = proposalService.stage(arguments, conversationId);

        if (outcome.isRefused()) {
            return Map.of(
                    "staged", false,
                    "error", outcome.refusalReason());
        }

        ProposalSummary proposal = outcome.proposal();

        // "staged" rather than "paid", and an explicit reminder, because the one
        // thing the model must not do is report this as a completed payment.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("staged", true);
        result.put("status", "AWAITING_USER_CONFIRMATION");
        result.put("proposalId", proposal.proposalId());
        result.put("billerName", proposal.billerName());
        result.put("maskedAccountNumber", proposal.maskedAccountNumber());
        result.put("amount", String.valueOf(proposal.amount()));
        result.put("currency", proposal.currency());
        result.put("expiresAt", proposal.expiresAt());
        result.put("note", "No money has moved. Tell the user these details and that they "
                + "must confirm separately. Do not say the payment has been made, sent or "
                + "scheduled.");

        return result;
    }
}
