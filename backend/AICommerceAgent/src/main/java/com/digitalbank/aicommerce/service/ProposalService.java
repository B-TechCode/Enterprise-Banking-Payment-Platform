package com.digitalbank.aicommerce.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.account.dto.AccountResponse;
import com.digitalbank.aicommerce.client.dto.BillerView;
import com.digitalbank.aicommerce.config.AgentProperties;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.domain.PaymentProposal;
import com.digitalbank.aicommerce.domain.ProposalStatus;
import com.digitalbank.aicommerce.dto.ProposalSummary;
import com.digitalbank.aicommerce.dto.StageOutcome;
import com.digitalbank.aicommerce.repo.PaymentProposalRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Validates and stages payment proposals. Never executes one.
 *
 * <p>This class is reachable from a tool the model can call, so it holds no
 * reference to the payment API, directly or through a collaborator. Execution
 * lives in {@link PaymentConfirmationService}, which is reached only from the
 * confirmation endpoint. The two are separate classes on purpose: a combined
 * class would put the payment client one method call away from model output,
 * whether or not any tool called that method. {@code
 * ToolsCannotReachPaymentClientTest} fails the build if that ever changes.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProposalService {

    private static final String PROPOSE_TOOL = "propose_bill_payment";
    private static final String CONFIRM_ACTION = "confirm_bill_payment";

    /** Conservative: an invoice reference goes into a payment record. */
    private static final Pattern INVOICE_REFERENCE =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,63}$");

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final PaymentProposalRepository proposalRepository;
    private final AccountQueryService accountQueryService;
    private final BillerQueryService billerQueryService;
    private final CallerIdentity callerIdentity;
    private final AuditService auditService;
    private final AgentProperties properties;

    // ---------------------------------------------------------------- staging

    /**
     * Validates a proposed payment and, if everything checks out, stages it for a
     * human to confirm. Never calls the payment orchestrator.
     *
     * @param arguments raw tool arguments as the model produced them; untrusted
     */
    @Transactional
    public StageOutcome stage(Map<String, Object> arguments, String conversationId) {

        String customerId;
        try {
            customerId = callerIdentity.requireCustomerId();
        } catch (RuntimeException denied) {
            audit(PROPOSE_TOOL, null, null, conversationId, arguments,
                    ActionOutcome.DENIED, denied.getMessage());
            throw denied;
        }

        String subject = callerIdentity.subject();

        // Identity comes from the token above. Nothing the model passed can
        // influence who this payment is for.
        String rawAccountId = asString(arguments.get("debtorAccountId"));
        String rawBillerRef = asString(arguments.get("billerReferenceNumber"));
        String rawAmount = asString(arguments.get("amount"));
        String rawInvoice = asString(arguments.get("invoiceReference"));

        UUID debtorAccountId;
        try {
            debtorAccountId = UUID.fromString(rawAccountId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return refuse(customerId, subject, conversationId, arguments,
                    "debtorAccountId is missing or not a valid account id. "
                            + "Call get_my_accounts and use an accountId from its result.");
        }

        AccountResponse account = accountQueryService.myAccounts(conversationId).stream()
                .filter(candidate -> debtorAccountId.equals(candidate.id()))
                .findFirst()
                .orElse(null);

        if (account == null) {
            // Either the account does not exist or it is not the caller's. The
            // model is told the same thing in both cases.
            return refuse(customerId, subject, conversationId, arguments,
                    "That account does not belong to this user. "
                            + "Use an accountId returned by get_my_accounts.");
        }

        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(account.status()))) {
            return refuse(customerId, subject, conversationId, arguments,
                    "That account is not active and cannot be used for a payment.");
        }

        // Normalised before it is checked, so an account recorded as " cad " is
        // not refused over its spelling. Account creation enforces ^[A-Z]{3}$,
        // but rows predating that rule, or written by another route, should not
        // cost a customer their payment.
        String currency = account.currency() == null ? null : account.currency().trim().toUpperCase();
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            return refuse(customerId, subject, conversationId, arguments,
                    "That account has no usable currency, so a payment cannot be prepared.");
        }

        // The orchestrator settles one currency only, so a payment from an
        // account in any other is refused here rather than staged and refused at
        // confirmation. Both currencies are named because the customer can act
        // on this: they need another account, and no retry will help.
        String settlementCurrency = properties.getSettlementCurrency().trim().toUpperCase();
        if (!settlementCurrency.equals(currency)) {
            return refuse(customerId, subject, conversationId, arguments,
                    "That account is held in " + currency
                            + ", and bill payments can only be made in " + settlementCurrency
                            + ". Use a " + settlementCurrency + " account instead.");
        }

        Optional<BillerView> owned =
                billerQueryService.findOwnedByReference(rawBillerRef, conversationId);

        if (owned.isEmpty()) {
            return refuse(customerId, subject, conversationId, arguments,
                    "That biller reference is not one of this user's billers. "
                            + "Call get_my_billers and use a referenceNumber from its result.");
        }

        BillerView biller = owned.get();

        if (!billerQueryService.isActiveInRegistry(rawBillerRef)) {
            return refuse(customerId, subject, conversationId, arguments,
                    "That biller is not currently active and cannot be paid.");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return refuse(customerId, subject, conversationId, arguments,
                    "amount is missing or not a valid decimal number. "
                            + "Ask the user how much they want to pay.");
        }

        if (amount.scale() > 2) {
            return refuse(customerId, subject, conversationId, arguments,
                    "amount must have at most two decimal places.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return refuse(customerId, subject, conversationId, arguments,
                    "amount must be greater than zero.");
        }

        if (amount.compareTo(properties.getMaxProposalAmount()) > 0) {
            return refuse(customerId, subject, conversationId, arguments,
                    "amount exceeds the limit this assistant may prepare ("
                            + properties.getMaxProposalAmount() + " " + currency
                            + "). Tell the user it must be arranged another way.");
        }

        String invoiceReference = rawInvoice == null ? null : rawInvoice.trim();

        if (invoiceReference == null || !INVOICE_REFERENCE.matcher(invoiceReference).matches()) {
            return refuse(customerId, subject, conversationId, arguments,
                    "invoiceReference is missing or not valid. "
                            + "Ask the user for the bill or invoice number; do not invent one.");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        PaymentProposal proposal = PaymentProposal.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .customerId(customerId)
                .subject(subject)
                .debtorAccountId(debtorAccountId)
                .debtorAccountMasked(account.maskedAccountNumber())
                .billerReferenceNumber(biller.referenceNumber())
                .billerName(biller.name())
                .invoiceReference(invoiceReference)
                .amount(amount)
                .currency(currency)
                .status(ProposalStatus.PENDING_CONFIRMATION)
                .createdAt(now)
                .expiresAt(now.plusSeconds(properties.getProposalTtlSeconds()))
                .build();

        PaymentProposal saved = proposalRepository.save(proposal);

        audit(PROPOSE_TOOL, customerId, subject, conversationId, arguments,
                ActionOutcome.SUCCESS,
                "staged proposal " + saved.getId() + " for " + amount + " " + currency);

        return StageOutcome.staged(toSummary(saved));
    }

    /**
     * Reads back a staged proposal so the chat reply can carry it.
     *
     * <p>Used by the orchestrator to build the proposal attached to the response.
     * Reading it from storage rather than from the tool result means what the
     * user is shown is the stored row, not a value that passed through anything
     * the model produced.</p>
     */
    @Transactional(readOnly = true)
    public Optional<ProposalSummary> findSummary(String proposalId, String customerId) {

        UUID id;
        try {
            id = UUID.fromString(proposalId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }

        return proposalRepository.findById(id)
                .filter(proposal -> customerId != null && customerId.equals(proposal.getCustomerId()))
                .map(this::toSummary);
    }

    // ---------------------------------------------------------------- helpers

    private StageOutcome refuse(String customerId, String subject, String conversationId,
                                Map<String, Object> arguments, String reason) {

        audit(PROPOSE_TOOL, customerId, subject, conversationId, arguments,
                ActionOutcome.DENIED, reason);

        return StageOutcome.refused(reason);
    }

    private void audit(String action, String customerId, String subject, String conversationId,
                       Map<String, Object> arguments, ActionOutcome outcome, String summary) {

        AgentActionLog entry = AgentActionLog.starting(action, customerId, subject, conversationId);
        entry.setInputJson(String.valueOf(arguments));

        auditService.record(entry, outcome, summary, null);
    }

    private ProposalSummary toSummary(PaymentProposal proposal) {
        return new ProposalSummary(
                proposal.getId().toString(),
                proposal.getBillerName(),
                proposal.getDebtorAccountMasked(),
                proposal.getAmount(),
                proposal.getCurrency(),
                proposal.getExpiresAt().toString());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
