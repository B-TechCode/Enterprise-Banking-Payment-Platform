package com.payments.orch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountOwnerResponse;
import com.commons.exception.ResourceNotFoundException;
import com.commons.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountClient;
import com.payments.orch.domain.Payment;
import com.payments.orch.domain.PaymentState;
import com.payments.orch.repo.OutboxRepo;
import com.payments.orch.repo.PaymentRepo;

/**
 * A payment is readable by the customer who asked for it, and by nobody else.
 *
 * <p>Until this check existed, any authenticated caller could read any payment
 * by its id: the amount, the biller, the invoice reference and the debtor
 * account. The payment row carries no owner of its own beyond the customer
 * recorded when it was accepted, which is what this compares against.</p>
 *
 * <p>Someone else's payment is reported as missing rather than refused. A
 * refusal would confirm the id exists, which is the question an attacker
 * enumerating ids is asking.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentReadAuthorizationTest {

    private static final String OWNER = "cust-owner";
    private static final String OTHER = "cust-other";

    @Mock private BillPayValidator validator;
    @Mock private AccountClient accounts;
    @Mock private PaymentRepo paymentRepo;
    @Mock private OutboxRepo outboxRepo;
    @Mock private ObjectMapper om;
    @Mock private CurrentUser currentUser;

    private BillPayOrchestrator orchestrator;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void setUp() {
        orchestrator = new BillPayOrchestrator(
                validator, accounts, paymentRepo, outboxRepo, om, currentUser);

        payment = Payment.builder()
                .paymentId(paymentId)
                .debtorAccountId(accountId)
                .customerId(OWNER)
                .amountValue(new BigDecimal("75.00"))
                .amountCcy("CAD")
                .state(PaymentState.POSTED)
                .build();

        when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    private void callerIs(String customerId) {
        when(currentUser.customerIdClaim()).thenReturn(Optional.ofNullable(customerId));
    }

    @Test
    @DisplayName("the customer who made the payment can read it")
    void ownerCanRead() {
        callerIs(OWNER);

        assertThat(orchestrator.view(paymentId).getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("another customer is told the payment does not exist")
    void otherCustomerSeesNotFound() {
        callerIs(OTHER);

        assertThatThrownBy(() -> orchestrator.view(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a token carrying no customer identity cannot read a payment")
    void tokenWithoutCustomerIdCannotRead() {
        // A service token, or a user token missing the claim. Neither owns a
        // payment, and a service has no reason to read one through this path.
        callerIs(null);

        assertThatThrownBy(() -> orchestrator.view(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a payment that does not exist reads the same as someone else's")
    void missingPaymentIsIndistinguishable() {
        callerIs(OWNER);
        when(paymentRepo.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.view(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a payment accepted before owners were recorded falls back to the account's owner")
    void legacyPaymentFallsBackToAccountOwner() {
        // Rows created before customer_id existed would otherwise become
        // unreadable by the very customer who made them.
        payment.setCustomerId(null);
        callerIs(OWNER);
        when(accounts.getOwner(accountId)).thenReturn(new AccountOwnerResponse(accountId, OWNER));

        assertThat(orchestrator.view(paymentId).getPaymentId()).isEqualTo(paymentId);
        verify(accounts).getOwner(accountId);
    }

    @Test
    @DisplayName("the fallback does not let another customer read a legacy payment")
    void legacyPaymentStillRefusesAnotherCustomer() {
        payment.setCustomerId(null);
        callerIs(OTHER);
        when(accounts.getOwner(accountId)).thenReturn(new AccountOwnerResponse(accountId, OWNER));

        assertThatThrownBy(() -> orchestrator.view(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a refused owner lookup denies the read rather than failing open")
    void refusedOwnerLookupDenies() {
        // Account Service applies its own ownership check to the relayed token,
        // so a refusal there is an answer: the caller does not own the account.
        payment.setCustomerId(null);
        callerIs(OTHER);
        when(accounts.getOwner(accountId)).thenThrow(new RuntimeException("403 Forbidden"));

        assertThatThrownBy(() -> orchestrator.view(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a payment with a recorded owner is answered without asking Account Service")
    void recordedOwnerNeedsNoLookup() {
        // view is the endpoint clients poll, so the common case must not make
        // an HTTP call per poll.
        callerIs(OWNER);

        orchestrator.view(paymentId);

        verify(accounts, never()).getOwner(any());
    }
}
