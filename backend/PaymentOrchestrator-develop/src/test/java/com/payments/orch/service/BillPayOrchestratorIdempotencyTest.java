package com.payments.orch.service;

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
