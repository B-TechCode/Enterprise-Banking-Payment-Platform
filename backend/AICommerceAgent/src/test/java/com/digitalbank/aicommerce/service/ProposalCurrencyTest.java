package com.digitalbank.aicommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

import com.account.dto.AccountResponse;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.digitalbank.aicommerce.client.dto.BillerView;
import com.digitalbank.aicommerce.config.AgentProperties;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.dto.StageOutcome;
import com.digitalbank.aicommerce.repo.PaymentProposalRepository;

/**
 * A payment is refused while it can still be explained.
 *
 * <p>The Payment Orchestrator settles one currency, refusing anything else with
 * CURRENCY_NOT_ALLOWED. The agent stages a proposal in whatever currency the
 * debtor account holds, so an account in another currency produced a proposal
 * that read as ready to confirm and was then refused at confirmation - by which
 * point the customer had already approved it, and no retry could help.</p>
 *
 * <p>Nothing under the payment record carries a currency at all: holds and
 * postings are bare amounts against an account whose currency is implied. So
 * this is not a step towards multi-currency, it is the agent declining to
 * prepare something the platform cannot settle.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProposalCurrencyTest {

    private static final String CUSTOMER = "cust-1";
    private static final String CONVERSATION = "conv-1";
    private static final String BILLER_REF = "ELEC-001";

    @Mock private PaymentProposalRepository proposalRepository;
    @Mock private AccountQueryService accountQueryService;
    @Mock private BillerQueryService billerQueryService;
    @Mock private CallerIdentity callerIdentity;
    @Mock private AuditService auditService;

    private final AgentProperties properties = new AgentProperties();
    private ProposalService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        service = new ProposalService(proposalRepository, accountQueryService, billerQueryService,
                callerIdentity, auditService, properties);

        accountId = UUID.randomUUID();

        when(callerIdentity.requireCustomerId()).thenReturn(CUSTOMER);
        when(callerIdentity.subject()).thenReturn("auth0|abc");
        when(proposalRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        when(billerQueryService.findOwnedByReference(eq(BILLER_REF), anyString()))
                .thenReturn(Optional.of(new BillerView("b1", "City Electric", BILLER_REF, "UTILITY", "ACTIVE")));
        when(billerQueryService.isActiveInRegistry(BILLER_REF)).thenReturn(true);
    }

    /** The caller holds one account, in this currency. */
    private void accountHeldIn(String currency) {
        when(accountQueryService.myAccounts(anyString())).thenReturn(List.of(
                new AccountResponse(accountId, CUSTOMER, "12345678", AccountType.CHEQUING,
                        AccountSubType.PERSONAL, AccountStatus.ACTIVE, currency, "Main",
                        "Everyday Chequing", new BigDecimal("500.00"), "****5678", 1)));
    }

    private StageOutcome stage() {
        return service.stage(Map.of(
                "debtorAccountId", accountId.toString(),
                "billerReferenceNumber", BILLER_REF,
                "amount", "75.00",
                "invoiceReference", "INV-2026-77"), CONVERSATION);
    }

    private ActionOutcome lastOutcome() {
        ArgumentCaptor<ActionOutcome> outcome = ArgumentCaptor.forClass(ActionOutcome.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce())
                .record(any(AgentActionLog.class), outcome.capture(), any(), any());
        return outcome.getValue();
    }

    @Test
    @DisplayName("a payment from an account in another currency is refused and recorded as DENIED")
    void foreignCurrencyAccountIsRefused() {

        accountHeldIn("USD");

        StageOutcome outcome = stage();

        assertThat(outcome.isRefused()).isTrue();
        assertThat(lastOutcome())
                .as("the customer cannot pay from this account; that is a refusal, not an outage")
                .isEqualTo(ActionOutcome.DENIED);
        verify(proposalRepository, never()).save(any());
    }

    @Test
    @DisplayName("the refusal names the account's currency and the one the platform settles")
    void refusalNamesBothCurrencies() {

        accountHeldIn("USD");

        // Without both, the customer is told no without being told what would
        // work, and no retry of this payment ever will.
        assertThat(stage().refusalReason())
                .contains("USD")
                .contains("CAD");
    }

    @Test
    @DisplayName("an account in the settlement currency still stages")
    void settlementCurrencyAccountStages() {

        // Positive control: proves the refusals above come from the currency
        // rule and not from a fixture that can never stage.
        accountHeldIn("CAD");

        StageOutcome outcome = stage();

        assertThat(outcome.isRefused()).isFalse();
        assertThat(outcome.proposal().amount()).isEqualByComparingTo("75.00");
        verify(proposalRepository).save(any());
    }

    @Test
    @DisplayName("the currency is compared without regard to case or surrounding space")
    void comparisonIgnoresCaseAndSpace() {

        // Account creation enforces ^[A-Z]{3}$, but rows predating that, or
        // written by another route, should not be refused over their spelling.
        accountHeldIn(" cad ");

        assertThat(stage().isRefused()).isFalse();
    }

    @Test
    @DisplayName("an account with no usable currency is still refused as before")
    void unusableCurrencyStillRefused() {

        accountHeldIn("C$");

        StageOutcome outcome = stage();

        assertThat(outcome.isRefused()).isTrue();
        assertThat(outcome.refusalReason()).contains("no usable currency");
        verify(proposalRepository, never()).save(any());
    }

    @Test
    @DisplayName("the rule follows the configured settlement currency, not a hardcoded CAD")
    void ruleFollowsConfiguration() {

        // Proves the currency is read from configuration. It is deliberately
        // not a feature switch: the orchestrator holds the same rule, so moving
        // this alone only moves where the refusal happens.
        properties.setSettlementCurrency("USD");

        accountHeldIn("USD");
        assertThat(stage().isRefused())
                .as("USD is now the settlement currency, so a USD account must stage")
                .isFalse();

        accountHeldIn("CAD");
        assertThat(stage().refusalReason()).contains("CAD").contains("USD");
    }
}
