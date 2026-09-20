package com.payments.orch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.HoldResponse;
import com.account.dto.HoldStatus;
import com.commons.exception.ForbiddenException;
import com.commons.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountClient;
import com.payments.orch.client.BillerRegistryClient;
import com.payments.orch.dto.AmountDto;
import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.repo.OutboxRepo;
import com.payments.orch.repo.PaymentRepo;

/**
 * A bill payment that fails validation must not touch the customer's money.
 *
 * <p>acceptBillPay validates before it asks Account Service to hold funds. The
 * order matters: were the hold placed first, a payment refused afterwards would
 * leave the customer's money held with no payment to release it, and nothing
 * recorded to show why.</p>
 *
 * <p>The validator here is the real one, so each rule is exercised as written:
 * the biller must be active, the execution date must not be in the past, and
 * the currency must be one the platform settles in. Only the biller registry
 * lookup is mocked.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillPayValidationGateTest {

    private static final String BILLER = "HYDRO-001";
    private static final String IDEM_KEY = "idem-key-validation";

    @Mock private BillerRegistryClient registry;
    @Mock private AccountClient accounts;
    @Mock private PaymentRepo paymentRepo;
    @Mock private OutboxRepo outboxRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private CurrentUser currentUser;

    private BillPayOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new BillPayOrchestrator(
                new BillPayValidator(registry), accounts, paymentRepo, outboxRepo, objectMapper,
                currentUser);

        when(paymentRepo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(registry.isActive(BILLER)).thenReturn(true);
    }

    private static BillPayRequest request(String biller, LocalDate executionDate, String currency) {
        return new BillPayRequest(
                UUID.randomUUID(),
                biller,
                "INV-001",
                executionDate.toString(),
                new AmountDto(new BigDecimal("250.00"), currency),
                "test");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("biller is not active",
                        request("CLOSED-BILLER", LocalDate.now(), "CAD"), "BILLER_INACTIVE"),
                Arguments.of("execution date is in the past",
                        request(BILLER, LocalDate.now().minusDays(1), "CAD"), "EXECUTION_DATE_PAST"),
                Arguments.of("currency is not CAD",
                        request(BILLER, LocalDate.now(), "USD"), "CURRENCY_NOT_ALLOWED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("a payment refused by validation places no hold and records nothing")
    void refusedPaymentLeavesMoneyAlone(String reason, BillPayRequest request, String code) {
        assertThatThrownBy(() -> orchestrator.acceptBillPay(request, IDEM_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(code);

        verifyNoInteractions(accounts);
        verify(paymentRepo, never()).save(any());
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("a payment refused by Account Service records nothing")
    void refusedHoldRecordsNothing() {
        // Account Service refuses a hold on an account the caller does not own.
        // The ErrorDecoder turns that 403 into a ForbiddenException, which the
        // shared handler reports as a 403 rather than a 500. What matters here
        // is that the refusal leaves no half-made payment behind.
        BillPayRequest valid = request(BILLER, LocalDate.now(), "CAD");
        when(accounts.placeHold(any(), any(), any()))
                .thenThrow(new ForbiddenException("not your account"));

        assertThatThrownBy(() -> orchestrator.acceptBillPay(valid, IDEM_KEY))
                .isInstanceOf(ForbiddenException.class);

        verify(paymentRepo, never()).save(any());
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("a valid payment places exactly one hold, on the requested account")
    void validPaymentPlacesOneHold() {
        // Positive control: proves the refusals above come from validation and
        // not from a fixture that can never reach the hold.
        BillPayRequest valid = request(BILLER, LocalDate.now(), "CAD");
        UUID holdId = UUID.randomUUID();
        when(accounts.placeHold(eq(valid.debtorAccountId()), eq(IDEM_KEY), any()))
                .thenReturn(new HoldResponse(holdId, new BigDecimal("250.00"), HoldStatus.ACTIVE, null, null));

        var response = orchestrator.acceptBillPay(valid, IDEM_KEY);

        assertThat(response.paymentId()).isEqualTo(holdId);
        verify(accounts).placeHold(eq(valid.debtorAccountId()), eq(IDEM_KEY), any());
        verify(paymentRepo).save(any());
    }
}
