package com.digitalbank.aicommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountResponse;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.commons.exception.ConflictException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;
import com.digitalbank.aicommerce.client.PaymentFeignClient;
import com.digitalbank.aicommerce.client.dto.BillPayCommand;
import com.digitalbank.aicommerce.client.dto.BillerView;
import com.digitalbank.aicommerce.client.dto.PaymentAcceptedView;
import com.digitalbank.aicommerce.config.AgentProperties;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.domain.PaymentProposal;
import com.digitalbank.aicommerce.domain.ProposalStatus;
import com.digitalbank.aicommerce.dto.StageOutcome;
import com.digitalbank.aicommerce.repo.PaymentProposalRepository;

/**
 * Covers the two halves that matter: what staging refuses, and what confirmation
 * does to a proposal's lifecycle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProposalServiceTest {

    private static final String CUSTOMER = "cust-1";
    private static final String CONVERSATION = "conv-1";
    private static final String BILLER_REF = "ELEC-001";

    @Mock private PaymentProposalRepository proposalRepository;
    @Mock private AccountQueryService accountQueryService;
    @Mock private BillerQueryService billerQueryService;
    @Mock private CallerIdentity callerIdentity;
    @Mock private AuditService auditService;
    @Mock private PaymentFeignClient paymentClient;

    private ProposalService service;
    private PaymentConfirmationService confirmationService;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();

        // Staging has no payment client to be given; only confirmation does.
        service = new ProposalService(proposalRepository, accountQueryService, billerQueryService,
                callerIdentity, auditService, properties);

        confirmationService = new PaymentConfirmationService(
                proposalRepository, callerIdentity, auditService, paymentClient);

        accountId = UUID.randomUUID();

        when(callerIdentity.requireCustomerId()).thenReturn(CUSTOMER);
        when(callerIdentity.subject()).thenReturn("auth0|abc");
        when(proposalRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(proposalRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void givenAnActiveAccountAndBiller() {
        when(accountQueryService.myAccounts(anyString())).thenReturn(List.of(account(AccountStatus.ACTIVE)));
        when(billerQueryService.findOwnedByReference(eq(BILLER_REF), anyString()))
                .thenReturn(Optional.of(new BillerView("b1", "City Electric", BILLER_REF, "UTILITY", "ACTIVE")));
        when(billerQueryService.isActiveInRegistry(BILLER_REF)).thenReturn(true);
    }

    /** In the settlement currency: staging refuses any other, per ProposalCurrencyTest. */
    private AccountResponse account(AccountStatus status) {
        return new AccountResponse(accountId, CUSTOMER, "12345678", AccountType.CHEQUING,
                AccountSubType.PERSONAL, status, "CAD", "Main", "Everyday Chequing",
                new BigDecimal("500.00"), "****5678", 1);
    }

    private Map<String, Object> validArguments() {
        return Map.of(
                "debtorAccountId", accountId.toString(),
                "billerReferenceNumber", BILLER_REF,
                "amount", "75.00",
                "invoiceReference", "INV-2026-77");
    }

    private ActionOutcome lastOutcome() {
        ArgumentCaptor<ActionOutcome> outcome = ArgumentCaptor.forClass(ActionOutcome.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce())
                .record(any(AgentActionLog.class), outcome.capture(), any(), any());
        return outcome.getValue();
    }

    @Nested
    @DisplayName("staging")
    class Staging {

        @Test
        @DisplayName("stages a pending proposal when everything checks out")
        void stagesWhenValid() {

            givenAnActiveAccountAndBiller();

            StageOutcome outcome = service.stage(validArguments(), CONVERSATION);

            assertThat(outcome.isRefused()).isFalse();
            assertThat(outcome.proposal().billerName()).isEqualTo("City Electric");
            assertThat(outcome.proposal().amount()).isEqualByComparingTo("75.00");

            ArgumentCaptor<PaymentProposal> saved = ArgumentCaptor.forClass(PaymentProposal.class);
            verify(proposalRepository).save(saved.capture());

            assertThat(saved.getValue().getStatus()).isEqualTo(ProposalStatus.PENDING_CONFIRMATION);
            assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER);
            // Staging cannot touch the orchestrator: ProposalService holds no
            // payment client at all, which ToolsCannotReachPaymentClientTest
            // enforces structurally.
            assertThat(saved.getValue().getPaymentId()).isNull();
        }

        @Test
        @DisplayName("refuses and records DENIED when the invoice reference is missing")
        void refusesMissingInvoiceReference() {

            givenAnActiveAccountAndBiller();

            Map<String, Object> incomplete = Map.of(
                    "debtorAccountId", accountId.toString(),
                    "billerReferenceNumber", BILLER_REF,
                    "amount", "75.00");

            StageOutcome outcome = service.stage(incomplete, CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("invoiceReference");
            assertThat(lastOutcome()).isEqualTo(ActionOutcome.DENIED);
            verify(proposalRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses an account that is not the caller's")
        void refusesForeignAccount() {

            givenAnActiveAccountAndBiller();

            Map<String, Object> arguments = new java.util.HashMap<>(validArguments());
            arguments.put("debtorAccountId", UUID.randomUUID().toString());

            StageOutcome outcome = service.stage(arguments, CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("does not belong");
            assertThat(lastOutcome()).isEqualTo(ActionOutcome.DENIED);
            verify(proposalRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a biller the caller has not registered")
        void refusesUnknownBiller() {

            when(accountQueryService.myAccounts(anyString()))
                    .thenReturn(List.of(account(AccountStatus.ACTIVE)));
            when(billerQueryService.findOwnedByReference(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            StageOutcome outcome = service.stage(validArguments(), CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("not one of this user's billers");
            verify(proposalRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses an amount above the configured ceiling")
        void refusesAmountOverLimit() {

            givenAnActiveAccountAndBiller();

            Map<String, Object> arguments = new java.util.HashMap<>(validArguments());
            arguments.put("amount", "5000.00");

            StageOutcome outcome = service.stage(arguments, CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("exceeds the limit");
            verify(proposalRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a frozen account")
        void refusesInactiveAccount() {

            when(accountQueryService.myAccounts(anyString()))
                    .thenReturn(List.of(account(AccountStatus.FROZEN)));

            StageOutcome outcome = service.stage(validArguments(), CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("not active");
            verify(proposalRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a non-numeric amount rather than throwing")
        void refusesUnparseableAmount() {

            givenAnActiveAccountAndBiller();

            Map<String, Object> arguments = new java.util.HashMap<>(validArguments());
            arguments.put("amount", "about seventy five");

            StageOutcome outcome = service.stage(arguments, CONVERSATION);

            assertThat(outcome.isRefused()).isTrue();
            assertThat(outcome.refusalReason()).contains("valid decimal");
        }
    }

    @Nested
    @DisplayName("confirmation")
    class Confirmation {

        private PaymentProposal pending() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            return PaymentProposal.builder()
                    .id(UUID.randomUUID())
                    .conversationId(CONVERSATION)
                    .customerId(CUSTOMER)
                    .debtorAccountId(accountId)
                    .debtorAccountMasked("****5678")
                    .billerReferenceNumber(BILLER_REF)
                    .billerName("City Electric")
                    .invoiceReference("INV-2026-77")
                    .amount(new BigDecimal("75.00"))
                    // Deliberately a currency staging can no longer produce.
                    // Confirmation rebuilds the payment from the stored row, so
                    // this pins that the currency is copied rather than assumed.
                    .currency("USD")
                    .status(ProposalStatus.PENDING_CONFIRMATION)
                    .createdAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .build();
        }

        @Test
        @DisplayName("executes the stored proposal and keys the payment on the proposal id")
        void executesStoredProposal() {

            PaymentProposal proposal = pending();
            UUID paymentId = UUID.randomUUID();

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
            when(paymentClient.billPay(anyString(), any()))
                    .thenReturn(new PaymentAcceptedView(paymentId, "ACCEPTED", "/api/v1/payments/" + paymentId));

            var response = confirmationService.confirm(proposal.getId());

            assertThat(response.paymentId()).isEqualTo(paymentId);
            assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.EXECUTED);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<BillPayCommand> command = ArgumentCaptor.forClass(BillPayCommand.class);
            verify(paymentClient).billPay(key.capture(), command.capture());

            assertThat(key.getValue())
                    .as("the idempotency key must be the proposal id")
                    .isEqualTo(proposal.getId().toString());

            // Every field comes from the stored row.
            assertThat(command.getValue().debtorAccountId()).isEqualTo(accountId);
            assertThat(command.getValue().billerReferenceNumber()).isEqualTo(BILLER_REF);
            assertThat(command.getValue().invoiceReference()).isEqualTo("INV-2026-77");
            assertThat(command.getValue().amount().value()).isEqualByComparingTo("75.00");
            assertThat(command.getValue().amount().currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("reports a proposal belonging to someone else as not found")
        void hidesForeignProposal() {

            PaymentProposal proposal = pending();
            proposal.setCustomerId("someone-else");

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

            assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(paymentClient, never()).billPay(any(), any());
        }

        @Test
        @DisplayName("refuses an expired proposal and marks it expired")
        void refusesExpired() {

            PaymentProposal proposal = pending();
            proposal.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

            assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("expired");

            assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.EXPIRED);
            verify(paymentClient, never()).billPay(any(), any());
        }

        @Test
        @DisplayName("leaves the proposal confirmed when the orchestrator fails, so a retry replays")
        void keepsConfirmedOnDownstreamFailure() {

            PaymentProposal proposal = pending();

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
            when(paymentClient.billPay(anyString(), any()))
                    .thenThrow(new IllegalStateException("orchestrator down"));

            assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                    .isInstanceOf(UpstreamException.class);

            assertThat(proposal.getStatus())
                    .as("reverting to pending would risk a second charge on retry")
                    .isEqualTo(ProposalStatus.CONFIRMED);
            assertThat(proposal.getPaymentId()).isNull();
        }

        @Test
        @DisplayName("an already executed proposal is not sent a second time")
        void doesNotResendAnExecutedProposal() {

            PaymentProposal proposal = pending();
            UUID paymentId = UUID.randomUUID();
            proposal.setStatus(ProposalStatus.EXECUTED);
            proposal.setPaymentId(paymentId);

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

            var response = confirmationService.confirm(proposal.getId());

            assertThat(response.paymentId()).isEqualTo(paymentId);
            verify(paymentClient, never()).billPay(any(), any());
        }

        @Test
        @DisplayName("a rejected proposal cannot be confirmed")
        void refusesRejectedProposal() {

            PaymentProposal proposal = pending();
            proposal.setStatus(ProposalStatus.REJECTED);

            when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

            assertThatThrownBy(() -> confirmationService.confirm(proposal.getId()))
                    .isInstanceOf(ConflictException.class);

            verify(paymentClient, never()).billPay(any(), any());
        }
    }
}
