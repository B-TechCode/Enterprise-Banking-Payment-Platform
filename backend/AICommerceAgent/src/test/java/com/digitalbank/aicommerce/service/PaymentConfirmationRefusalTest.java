package com.digitalbank.aicommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.InsufficientFundsException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;
import com.digitalbank.aicommerce.client.PaymentFeignClient;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.domain.PaymentProposal;
import com.digitalbank.aicommerce.domain.ProposalStatus;
import com.digitalbank.aicommerce.repo.PaymentProposalRepository;

/**
 * What the customer is told when the orchestrator refuses a confirmed payment.
 *
 * <p>Until the agent gained an ErrorDecoder, every answer from the orchestrator
 * arrived as a FeignException and left this method as a 502: a payment declined
 * for want of funds, or from an account the caller does not own, was reported as
 * the assistant having broken. These tests pin the distinction between a refusal
 * the customer can act on and a failure that is ours.</p>
 *
 * <p>Every case asserts the same two things besides the exception: the proposal
 * is left CONFIRMED with no payment id. Nothing was charged, and a retry must
 * replay against the same Idempotency-Key rather than stage a second payment.
 * {@code PaymentConfirmationRollbackTest} covers the annotation that makes that
 * survive a real transaction.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentConfirmationRefusalTest {

    private static final String CUSTOMER = "cust-1";

    @Mock private PaymentProposalRepository proposalRepository;
    @Mock private CallerIdentity callerIdentity;
    @Mock private AuditService auditService;
    @Mock private PaymentFeignClient paymentClient;

    private PaymentConfirmationService confirmationService;
    private PaymentProposal proposal;

    @BeforeEach
    void setUp() {
        confirmationService = new PaymentConfirmationService(
                proposalRepository, callerIdentity, auditService, paymentClient);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        proposal = PaymentProposal.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-1")
                .customerId(CUSTOMER)
                .debtorAccountId(UUID.randomUUID())
                .debtorAccountMasked("****5678")
                .billerReferenceNumber("ELEC-001")
                .billerName("City Electric")
                .invoiceReference("INV-2026-77")
                .amount(new BigDecimal("75.00"))
                .currency("USD")
                .status(ProposalStatus.PENDING_CONFIRMATION)
                .createdAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();

        when(callerIdentity.requireCustomerId()).thenReturn(CUSTOMER);
        when(callerIdentity.subject()).thenReturn("auth0|abc");
        when(proposalRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(proposalRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
    }

    private void orchestratorThrows(RuntimeException refusal) {
        when(paymentClient.billPay(anyString(), any())).thenThrow(refusal);
    }

    private ActionOutcome lastOutcome() {
        ArgumentCaptor<ActionOutcome> outcome = ArgumentCaptor.forClass(ActionOutcome.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce())
                .record(any(AgentActionLog.class), outcome.capture(), any(), any());
        return outcome.getValue();
    }

    private void assertNothingWasCharged() {
        assertThat(proposal.getStatus())
                .as("reverting to pending would risk a second charge on retry")
                .isEqualTo(ProposalStatus.CONFIRMED);
        assertThat(proposal.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("an account the caller may not pay from is reported as a refusal, not a failure")
    void forbiddenSurfacesAsForbidden() {

        orchestratorThrows(new ForbiddenException("That account is not yours to pay from"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .as("wrapping this in UpstreamException would tell the customer we broke")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("That account is not yours to pay from");

        assertNothingWasCharged();
    }

    @Test
    @DisplayName("a refused payment is audited DENIED, not ERROR")
    void forbiddenIsAuditedAsDenied() {

        orchestratorThrows(new ForbiddenException("That account is not yours to pay from"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .isInstanceOf(ForbiddenException.class);

        // An attempt to pay from an account that is not the caller's is a
        // refusal to record as one; filed under ERROR it reads as an outage and
        // would not stand out to anyone reviewing the log.
        assertThat(lastOutcome()).isEqualTo(ActionOutcome.DENIED);
    }

    @Test
    @DisplayName("not enough money is reported as such, and audited ERROR")
    void insufficientFundsSurfacesAsInsufficientFunds() {

        orchestratorThrows(new InsufficientFundsException());

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(lastOutcome()).isEqualTo(ActionOutcome.ERROR);
        assertNothingWasCharged();
    }

    @Test
    @DisplayName("a missing account or biller is reported as not found")
    void notFoundSurfacesAsNotFound() {

        orchestratorThrows(new ResourceNotFoundException("The account or biller for this payment was not found"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertNothingWasCharged();
    }

    @Test
    @DisplayName("a downstream conflict is reported as a conflict")
    void conflictSurfacesAsConflict() {

        orchestratorThrows(new ConflictException("Something about this payment changed; please ask again"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .isInstanceOf(ConflictException.class);

        assertNothingWasCharged();
    }

    @Test
    @DisplayName("an orchestrator that does not answer is still an upstream failure")
    void unexpectedFailureStaysUpstream() {

        // The counterweight to the cases above: narrowing the catch must not
        // turn every failure into something the customer is blamed for.
        orchestratorThrows(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("has not been sent");

        assertThat(lastOutcome()).isEqualTo(ActionOutcome.ERROR);
        assertNothingWasCharged();
    }

    @Test
    @DisplayName("a refusal carries nothing of the orchestrator's own wording beyond the decoder's message")
    void refusalMessageIsTheDecodersOwn() {

        // The decoder is what writes these messages; this method must pass one
        // through unaltered rather than appending anything of its own.
        orchestratorThrows(new ConflictException("Something about this payment changed; please ask again"));

        assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                .hasMessage("Something about this payment changed; please ask again");
    }
}
