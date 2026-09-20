package com.digitalbank.aicommerce.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.InsufficientFundsException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;
import com.digitalbank.aicommerce.client.PaymentFeignClient;
import com.digitalbank.aicommerce.client.dto.BillPayCommand;
import com.digitalbank.aicommerce.client.dto.MoneyAmount;
import com.digitalbank.aicommerce.client.dto.PaymentAcceptedView;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.domain.PaymentProposal;
import com.digitalbank.aicommerce.domain.ProposalStatus;
import com.digitalbank.aicommerce.dto.PaymentConfirmationResponse;
import com.digitalbank.aicommerce.repo.PaymentProposalRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes a staged proposal once the user has confirmed it.
 *
 * <p>This is the only class in the service that holds {@link PaymentFeignClient},
 * and it is deliberately separate from {@link ProposalService}. Staging is
 * reachable from a tool the model can call; this class must not be. Were the two
 * combined, the payment client would sit one method call away from model output
 * even if no tool ever called that method, which is the exposure the design
 * exists to rule out. {@code ToolsCannotReachPaymentClientTest} enforces the
 * separation.</p>
 *
 * <p>No language model takes part here. The payment is rebuilt entirely from the
 * stored proposal, so nothing said between proposing and confirming can change
 * the amount, the biller or the account. The proposal id is sent as the
 * Idempotency-Key, so a repeated confirmation settles onto the original payment
 * instead of creating a second one.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmationService {

    private static final String CONFIRM_ACTION = "confirm_bill_payment";

    private final PaymentProposalRepository proposalRepository;
    private final CallerIdentity callerIdentity;
    private final AuditService auditService;

    /** The only reference in the service to an API that moves money. */
    private final PaymentFeignClient paymentClient;

    /**
     * Executes a staged proposal.
     *
     * <p>The transaction deliberately does not roll back on the failures below. A
     * proposal that has been moved to CONFIRMED must stay CONFIRMED even when the
     * orchestrator call fails, so the retry replays against the same
     * Idempotency-Key rather than being staged and charged twice. The same
     * applies to marking a lapsed proposal EXPIRED.</p>
     */
    @Transactional(noRollbackFor = {
            UpstreamException.class, ConflictException.class, ResourceNotFoundException.class,
            // Refusals from the orchestrator reach this method now that
            // downstream statuses are translated rather than flattened. They
            // belong here for the same reason as the rest: a rollback would
            // return the proposal to PENDING_CONFIRMATION and quietly undo the
            // marker that makes a retry replay against the same
            // Idempotency-Key. PaymentConfirmationRollbackTest pins the list.
            ForbiddenException.class, InsufficientFundsException.class })
    public PaymentConfirmationResponse confirm(UUID proposalId) {

        String customerId = callerIdentity.requireCustomerId();
        String subject = callerIdentity.subject();

        PaymentProposal proposal = proposalRepository.findById(proposalId).orElse(null);

        // A proposal belonging to someone else is reported exactly as a missing
        // one, so this endpoint cannot be used to discover which ids exist.
        if (proposal == null || !customerId.equals(proposal.getCustomerId())) {
            audit(customerId, subject, null, proposalId, ActionOutcome.DENIED,
                    "proposal not found for this customer");
            throw new ResourceNotFoundException("Proposal not found");
        }

        String conversationId = proposal.getConversationId();

        // Already executed: report the original payment rather than sending a
        // second one.
        if (proposal.getStatus() == ProposalStatus.EXECUTED) {
            audit(customerId, subject, conversationId, proposalId, ActionOutcome.SUCCESS,
                    "already executed as payment " + proposal.getPaymentId());
            return toConfirmation(proposal, null);
        }

        if (proposal.getStatus() != ProposalStatus.PENDING_CONFIRMATION
                && proposal.getStatus() != ProposalStatus.CONFIRMED) {
            audit(customerId, subject, conversationId, proposalId, ActionOutcome.DENIED,
                    "proposal is " + proposal.getStatus());
            throw new ConflictException("This proposal can no longer be confirmed");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Expiry applies to a proposal awaiting confirmation. One already
        // confirmed was approved in time and is only being retried.
        if (proposal.getStatus() == ProposalStatus.PENDING_CONFIRMATION
                && proposal.isExpiredAt(now)) {

            proposal.setStatus(ProposalStatus.EXPIRED);
            proposalRepository.save(proposal);

            audit(customerId, subject, conversationId, proposalId, ActionOutcome.DENIED,
                    "proposal expired at " + proposal.getExpiresAt());

            throw new ConflictException("This proposal has expired. Please ask again.");
        }

        if (proposal.getStatus() == ProposalStatus.PENDING_CONFIRMATION) {
            // Under the @Version column, two confirmations racing here cannot
            // both win.
            proposal.setStatus(ProposalStatus.CONFIRMED);
            proposal.setConfirmedAt(now);
            proposalRepository.saveAndFlush(proposal);
        }

        // Built entirely from the stored row.
        BillPayCommand command = new BillPayCommand(
                proposal.getDebtorAccountId(),
                proposal.getBillerReferenceNumber(),
                proposal.getInvoiceReference(),
                LocalDate.now(ZoneOffset.UTC).toString(),
                new MoneyAmount(proposal.getAmount(), proposal.getCurrency()),
                null);

        PaymentAcceptedView accepted;
        try {
            accepted = paymentClient.billPay(proposal.getId().toString(), command);

        } catch (ForbiddenException refused) {
            // The orchestrator declined the payment itself, rather than failing
            // to answer. Reporting that as a server error would tell the
            // customer the assistant broke when in fact their payment was
            // refused, and for a reason they may be able to act on.
            log.warn("payment refused for proposal={}: {}", proposal.getId(), refused.getMessage());

            audit(customerId, subject, conversationId, proposalId, ActionOutcome.DENIED,
                    "orchestrator refused the payment: " + refused.getMessage());

            throw refused;

        } catch (InsufficientFundsException | ConflictException | ResourceNotFoundException refused) {
            log.warn("payment declined for proposal={}: {}", proposal.getId(), refused.getMessage());

            audit(customerId, subject, conversationId, proposalId, ActionOutcome.ERROR,
                    "orchestrator declined the payment: " + refused.getMessage());

            throw refused;

        } catch (RuntimeException failure) {
            log.error("payment orchestrator did not answer for proposal={}", proposal.getId(), failure);

            audit(customerId, subject, conversationId, proposalId, ActionOutcome.ERROR,
                    "orchestrator call failed: " + failure.getMessage());

            // Left CONFIRMED on purpose: retrying replays the same
            // Idempotency-Key, which is safe. Returning it to pending is not.
            // The same holds for the refusals above: nothing was charged, and
            // the proposal stays confirmable so a retry can settle it.
            throw new UpstreamException(
                    "The payment could not be submitted. It has not been sent; you can retry.");
        }

        if (accepted == null || accepted.paymentId() == null) {
            audit(customerId, subject, conversationId, proposalId, ActionOutcome.ERROR,
                    "orchestrator returned no payment id");
            throw new UpstreamException("The payment could not be submitted. You can retry.");
        }

        proposal.setStatus(ProposalStatus.EXECUTED);
        proposal.setPaymentId(accepted.paymentId());
        proposalRepository.save(proposal);

        audit(customerId, subject, conversationId, proposalId, ActionOutcome.SUCCESS,
                "executed as payment " + accepted.paymentId());

        return toConfirmation(proposal, accepted);
    }

    private void audit(String customerId, String subject, String conversationId,
                       UUID proposalId, ActionOutcome outcome, String summary) {

        AgentActionLog entry = AgentActionLog.starting(
                CONFIRM_ACTION, customerId, subject, conversationId);
        entry.setInputJson(String.valueOf(Map.of("proposalId", proposalId)));

        auditService.record(entry, outcome, summary, null);
    }

    private PaymentConfirmationResponse toConfirmation(PaymentProposal proposal,
                                                       PaymentAcceptedView accepted) {
        return new PaymentConfirmationResponse(
                proposal.getId(),
                proposal.getPaymentId(),
                proposal.getStatus().name(),
                accepted == null ? null : accepted.state(),
                accepted == null ? null : accepted.statusUrl());
    }
}
