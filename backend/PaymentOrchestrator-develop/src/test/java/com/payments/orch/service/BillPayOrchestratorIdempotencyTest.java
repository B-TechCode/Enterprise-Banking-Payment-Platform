package com.payments.orch.service;

import com.account.dto.HoldResponse;
import com.account.dto.HoldStatus;
import com.commons.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountClient;
import com.payments.orch.domain.Payment;
import com.payments.orch.domain.PaymentState;
import com.payments.orch.dto.AmountDto;
import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.repo.OutboxRepo;
import com.payments.orch.repo.PaymentRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Smoke tests for payment idempotency.
 *
 * <p>Replaying a bill payment with an Idempotency-Key that has already been
 * accepted must return the original payment and must not create a second
 * payment, a second account hold, or a second outbox event.</p>
 */
@ExtendWith(MockitoExtension.class)
class BillPayOrchestratorIdempotencyTest {

    @Mock private BillPayValidator validator;
    @Mock private AccountClient accounts;
    @Mock private PaymentRepo paymentRepo;
    @Mock private OutboxRepo outboxRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private CurrentUser currentUser;

    @InjectMocks private BillPayOrchestrator orchestrator;

    private static final String IDEM_KEY = "idem-key-001";

    private BillPayRequest request() {
        return new BillPayRequest(
                UUID.randomUUID(),
                "HYDRO-001",
                "INV-001",
                LocalDate.now().toString(),
                new AmountDto(new BigDecimal("250.00"), "CAD"),
                "test");
    }

    @Test
    @DisplayName("the payment id is the hold id: release and capture depend on it")
    void paymentIdIsTheHoldId() {
        // StatusConsumer passes the payment id where Account Service expects a
        // hold id, for both release and capture. Nothing enforces that beyond
        // the line in acceptBillPay that takes one from the other, so it is
        // asserted here: change either side and this fails rather than a
        // payment silently failing to settle.
        UUID holdId = UUID.randomUUID();
        var request = request();

        when(paymentRepo.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.empty());
        when(accounts.placeHold(any(), any(), any())).thenReturn(
                new HoldResponse(holdId, new BigDecimal("250.00"), HoldStatus.ACTIVE, null, null));

        var response = orchestrator.acceptBillPay(request, IDEM_KEY);

        assertThat(response.paymentId())
                .as("the payment must be identified by the hold placed for it")
                .isEqualTo(holdId);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo).save(saved.capture());
        assertThat(saved.getValue().getPaymentId()).isEqualTo(holdId);
    }

    @Test
    @DisplayName("the customer who asked for the payment is recorded on it")
    void recordsTheRequestingCustomer() {
        // What makes the payment readable by its owner and nobody else.
        when(paymentRepo.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.empty());
        when(currentUser.customerIdClaim()).thenReturn(Optional.of("cust-1"));
        when(accounts.placeHold(any(), any(), any())).thenReturn(
                new HoldResponse(UUID.randomUUID(), new BigDecimal("250.00"), HoldStatus.ACTIVE, null, null));

        orchestrator.acceptBillPay(request(), IDEM_KEY);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo).save(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo("cust-1");
    }

    @Test
    @DisplayName("replaying a known Idempotency-Key returns the original payment")
    void replayReturnsExistingPayment() {
        UUID existingId = UUID.randomUUID();
        Payment existing = Payment.builder()
                .paymentId(existingId)
                .state(PaymentState.POSTED)
                .idempotencyKey(IDEM_KEY)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(paymentRepo.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing));

        var response = orchestrator.acceptBillPay(request(), IDEM_KEY);

        assertThat(response.paymentId()).isEqualTo(existingId);
        assertThat(response.state()).isEqualTo(PaymentState.POSTED.name());
    }

    @Test
    @DisplayName("replay creates no second payment, hold or outbox event")
    void replayHasNoSideEffects() {
        Payment existing = Payment.builder()
                .paymentId(UUID.randomUUID())
                .state(PaymentState.FUNDS_HELD)
                .idempotencyKey(IDEM_KEY)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(paymentRepo.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing));

        orchestrator.acceptBillPay(request(), IDEM_KEY);

        // The short-circuit must happen before validation, the account hold,
        // and any persistence.
        verifyNoInteractions(validator);
        verifyNoInteractions(accounts);
        verifyNoInteractions(outboxRepo);
        verify(paymentRepo, never()).save(any());
    }
}
