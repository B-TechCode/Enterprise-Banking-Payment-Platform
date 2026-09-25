package com.payments.orch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.CreateHoldRequest;
import com.account.dto.HoldResponse;
import com.account.dto.HoldStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountClient;
import com.payments.orch.dto.AmountDto;
import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.repo.OutboxRepo;
import com.payments.orch.repo.PaymentRepo;
import com.commons.security.CurrentUser;

/**
 * The currency a payment is stated in reaches the service that holds the
 * account.
 *
 * <p>AccountService refuses a hold whose currency is not the account's, but it
 * can only do that if it is told. This pins the other half: the orchestrator
 * forwards the payment's own currency rather than omitting it or substituting a
 * default, which would put the 1:1 debit back within reach.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillPayHoldCurrencyTest {

    private static final UUID DEBTOR = UUID.randomUUID();

    @Mock private BillPayValidator validator;
    @Mock private AccountClient accounts;
    @Mock private PaymentRepo paymentRepo;
    @Mock private OutboxRepo outboxRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private CurrentUser currentUser;

    @InjectMocks private BillPayOrchestrator orchestrator;

    private BillPayRequest paymentIn(String currency) {
        return new BillPayRequest(
                DEBTOR,
                "HYDRO-001",
                "INV-001",
                LocalDate.now().toString(),
                new AmountDto(new BigDecimal("250.00"), currency),
                "test");
    }

    private CreateHoldRequest captureHoldRequest(String currency) {
        when(paymentRepo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(currentUser.customerIdClaim()).thenReturn(Optional.of("cust-1"));
        when(accounts.placeHold(eq(DEBTOR), anyString(), any(CreateHoldRequest.class)))
                .thenReturn(new HoldResponse(
                        UUID.randomUUID(), new BigDecimal("250.00"), HoldStatus.ACTIVE, null, null));

        orchestrator.acceptBillPay(paymentIn(currency), "idem-key-1");

        ArgumentCaptor<CreateHoldRequest> captor = ArgumentCaptor.forClass(CreateHoldRequest.class);
        org.mockito.Mockito.verify(accounts)
                .placeHold(eq(DEBTOR), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the hold carries the currency the payment was stated in")
    void holdCarriesThePaymentCurrency() {
        assertThat(captureHoldRequest("CAD").currency()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("the currency is forwarded, not replaced by a settlement default")
    void currencyIsNotSubstituted() {
        // If this ever hardcoded the settlement currency, a payment in another
        // currency would be held against the account as though it matched - the
        // exact failure the check in AccountService exists to catch, arriving
        // pre-disguised so that check could never fire.
        assertThat(captureHoldRequest("USD").currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("the amount and the currency describe the same money")
    void amountAndCurrencyTravelTogether() {
        CreateHoldRequest held = captureHoldRequest("CAD");

        assertThat(held.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(held.currency()).isEqualTo("CAD");
    }
}
