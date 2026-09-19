package com.payments.orch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.events.billpay.BillBatchSubmitted;
import com.events.billpay.BillPayEnqueued;
import com.events.billpay.BillpayStatusEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountM2MClient;
import com.payments.orch.domain.Payment;
import com.payments.orch.domain.PaymentState;
import com.payments.orch.repo.PaymentRepo;
import com.payments.orch.repo.ProcessedEventRepo;

/**
 * Redelivery safety of the three consumers that drive a payment's lifecycle.
 *
 * <p>Kafka delivers at least once and does not guarantee order across topics.
 * Each consumer already skips an event whose id it has processed, but that is
 * not enough: Settlement gives every billpay.status event a fresh random id, so
 * one confirmation handled twice arrives as two different events. Before these
 * guards, that second POSTED debited the customer again, a late FAILED marked a
 * debited payment as failed, and a late batch event could move a finished
 * payment backwards, reopening it to the duplicate.</p>
 *
 * <p>The rule now is that a payment only moves forward and a finished one
 * (POSTED or FAILED) is never touched. Tests in the "regressions" group fail
 * against the code before that rule; those in "existing protections" passed
 * before it and must keep passing.</p>
 *
 * <p>Not covered, because this change does not fix it: a debit that succeeds at
 * Account Service followed by a failed local commit. The remote debit cannot be
 * rolled back from here and the event is redelivered. That needs an idempotent
 * debit on the Account Service side and is tracked separately.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentEventRedeliveryTest {

    @Mock private PaymentRepo paymentRepo;
    @Mock private ProcessedEventRepo processed;
    @Mock private ObjectMapper om;
    @Mock private AccountM2MClient accounts;

    private StatusConsumer statusConsumer;
    private EnqueuedConsumer enqueuedConsumer;
    private SubmittedConsumer submittedConsumer;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void setUp() {
        statusConsumer = new StatusConsumer(paymentRepo, processed, om, accounts);
        enqueuedConsumer = new EnqueuedConsumer(paymentRepo, processed, om);
        submittedConsumer = new SubmittedConsumer(paymentRepo, processed, om);

        payment = paymentIn(PaymentState.SUBMITTED, paymentId);
        when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    private Payment paymentIn(PaymentState state, UUID id) {
        return Payment.builder()
                .paymentId(id)
                .debtorAccountId(accountId)
                .batchId(batchId)
                .amountValue(new BigDecimal("75.00"))
                .amountCcy("USD")
                .state(state)
                .build();
    }

    // ------------------------------------------------------ deliveries

    /** Delivers a status event. Each call carries a new event id, as Settlement's do. */
    private void deliverStatus(String status) throws Exception {
        deliverStatus(status, UUID.randomUUID());
    }

    private void deliverStatus(String status, UUID eventId) throws Exception {
        String payload = "status-" + eventId;
        when(om.readValue(payload, BillpayStatusEvent.class)).thenReturn(new BillpayStatusEvent(
                eventId, paymentId, batchId, status, "reason", OffsetDateTime.now()));
        statusConsumer.onMessage(payload);
    }

    private void deliverEnqueued(String eventId) throws Exception {
        String payload = "enqueued-" + eventId;
        when(om.readValue(payload, BillPayEnqueued.class)).thenReturn(BillPayEnqueued.builder()
                .eventId(eventId).paymentId(paymentId).batchId(batchId).build());
        enqueuedConsumer.onMessage(payload);
    }

    private void deliverSubmitted(String eventId) throws Exception {
        String payload = "submitted-" + eventId;
        when(om.readValue(payload, BillBatchSubmitted.class)).thenReturn(BillBatchSubmitted.builder()
                .eventId(eventId).batchId(batchId.toString()).build());
        submittedConsumer.onMessage(payload);
    }

    private void verifyNoMoneyMoved() {
        verify(accounts, never()).debit(any(), any(), any());
        verify(accounts, never()).releaseHold(any(), any());
    }

    // ------------------------------------------------------ regressions

    @Nested
    @DisplayName("regressions: a finished payment is never touched")
    class Regressions {

        @Test
        @DisplayName("a second POSTED for an already POSTED payment does not debit again")
        void duplicatePostedDoesNotDebitTwice() throws Exception {
            payment.setState(PaymentState.POSTED);

            deliverStatus("POSTED");

            verifyNoMoneyMoved();
            assertThat(payment.getState()).isEqualTo(PaymentState.POSTED);
        }

        @Test
        @DisplayName("a late FAILED does not mark a debited payment as failed")
        void lateFailedDoesNotFlipPosted() throws Exception {
            payment.setState(PaymentState.POSTED);

            deliverStatus("FAILED");

            verifyNoMoneyMoved();
            assertThat(payment.getState()).isEqualTo(PaymentState.POSTED);
        }

        @Test
        @DisplayName("a late POSTED does not debit a payment that already FAILED and released its hold")
        void latePostedDoesNotDebitFailed() throws Exception {
            // The hold was released when the payment failed. Debiting now would
            // take money for a payment the platform already reported as failed.
            payment.setState(PaymentState.FAILED);

            deliverStatus("POSTED");

            verifyNoMoneyMoved();
            assertThat(payment.getState()).isEqualTo(PaymentState.FAILED);
        }

        @Test
        @DisplayName("a late enqueue event does not move a finished payment back to BATCHED")
        void lateEnqueueDoesNotReopen() throws Exception {
            payment.setState(PaymentState.POSTED);

            deliverEnqueued("enq-late");

            assertThat(payment.getState()).isEqualTo(PaymentState.POSTED);
            verify(paymentRepo, never()).save(any());
        }

        @Test
        @DisplayName("a late batch-submitted event leaves finished payments alone and advances the rest")
        void lateSubmittedOnlyAdvancesUnfinished() throws Exception {
            Payment posted = paymentIn(PaymentState.POSTED, UUID.randomUUID());
            Payment failed = paymentIn(PaymentState.FAILED, UUID.randomUUID());
            Payment batched = paymentIn(PaymentState.BATCHED, UUID.randomUUID());
            when(paymentRepo.findAllByBatchId(batchId)).thenReturn(List.of(posted, failed, batched));

            deliverSubmitted("sub-late");

            assertThat(posted.getState()).isEqualTo(PaymentState.POSTED);
            assertThat(failed.getState()).isEqualTo(PaymentState.FAILED);
            assertThat(batched.getState()).isEqualTo(PaymentState.SUBMITTED);
        }

        @Test
        @DisplayName("a late batch event followed by a duplicate confirmation still debits only once")
        void reopenThenDuplicateDoesNotDebitTwice() throws Exception {
            // The chained failure the state guard must close on every consumer,
            // not just StatusConsumer: a late batch event moves a POSTED payment
            // back to SUBMITTED, after which a duplicate POSTED would look like a
            // first confirmation and debit again.
            when(paymentRepo.findAllByBatchId(batchId)).thenReturn(List.of(payment));

            deliverStatus("POSTED");          // the genuine confirmation: debits once
            deliverSubmitted("sub-late");     // late batch event
            deliverStatus("POSTED");          // duplicate confirmation, new event id

            verify(accounts, times(1)).debit(eq(accountId), any(), any());
            assertThat(payment.getState()).isEqualTo(PaymentState.POSTED);
        }
    }

    // --------------------------------------------------- the skip path

    /**
     * Not a regression test: it passes against the old code too, where the
     * duplicate was fully processed (and debited) and then recorded. It guards
     * the new skip branch instead. A skipped event must still be recorded, or
     * Kafka would keep redelivering it.
     */
    @Test
    @DisplayName("an event skipped for a finished payment is still recorded, so it is not redelivered")
    void skippedEventIsRecorded() throws Exception {
        payment.setState(PaymentState.POSTED);

        deliverStatus("POSTED");

        verify(processed).save(any());
    }

    // ----------------------------------------------- existing protections

    @Nested
    @DisplayName("existing protections")
    class ExistingProtections {

        @Test
        @DisplayName("a POSTED for an unfinished payment releases the hold, then debits once")
        void postedReleasesThenDebitsOnce() throws Exception {
            // Positive control: proves the path the guards protect still runs.
            deliverStatus("POSTED");

            InOrder order = inOrder(accounts);
            order.verify(accounts).releaseHold(accountId, paymentId);
            order.verify(accounts).debit(eq(accountId), any(), any());
            verify(accounts, times(1)).debit(any(), any(), any());
            assertThat(payment.getState()).isEqualTo(PaymentState.POSTED);
        }

        @Test
        @DisplayName("a FAILED for an unfinished payment releases the hold and does not debit")
        void failedReleasesWithoutDebit() throws Exception {
            deliverStatus("FAILED");

            verify(accounts).releaseHold(accountId, paymentId);
            verify(accounts, never()).debit(any(), any(), any());
            assertThat(payment.getState()).isEqualTo(PaymentState.FAILED);
        }

        @Test
        @DisplayName("the same status event redelivered after processing is skipped")
        void sameStatusEventSkipped() throws Exception {
            UUID eventId = UUID.randomUUID();
            when(processed.existsByHandlerAndEventId("status", eventId.toString())).thenReturn(true);

            deliverStatus("POSTED", eventId);

            verifyNoInteractions(accounts);
            assertThat(payment.getState()).isEqualTo(PaymentState.SUBMITTED);
        }

        @Test
        @DisplayName("a status event for an unknown payment moves no money")
        void unknownPaymentIgnored() throws Exception {
            when(paymentRepo.findById(paymentId)).thenReturn(Optional.empty());

            deliverStatus("POSTED");

            verifyNoInteractions(accounts);
        }

        @Test
        @DisplayName("the same enqueue event redelivered is skipped")
        void sameEnqueueEventSkipped() throws Exception {
            payment.setState(PaymentState.FUNDS_HELD);
            when(processed.existsByHandlerAndEventId("enqueued", "enq-1")).thenReturn(true);

            deliverEnqueued("enq-1");

            assertThat(payment.getState()).isEqualTo(PaymentState.FUNDS_HELD);
        }

        @Test
        @DisplayName("the same batch-submitted event redelivered is skipped")
        void sameSubmittedEventSkipped() throws Exception {
            when(processed.existsByHandlerAndEventId("submitted", "sub-1")).thenReturn(true);

            deliverSubmitted("sub-1");

            verify(paymentRepo, never()).findAllByBatchId(any());
        }

        @Test
        @DisplayName("an enqueue event batches a payment whose funds are held")
        void enqueueBatchesHeldPayment() throws Exception {
            payment.setState(PaymentState.FUNDS_HELD);

            deliverEnqueued("enq-2");

            assertThat(payment.getState()).isEqualTo(PaymentState.BATCHED);
            verify(paymentRepo).save(payment);
        }
    }
}
