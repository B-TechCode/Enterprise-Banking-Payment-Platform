package com.settlement.service;

import com.events.billpay.BillBatchReadyEvent;
import com.events.billpay.BillBatchRetryEvent;
import com.settlement.domain.BillBatchSettlement;
import com.settlement.domain.SettlementStatus;
import com.settlement.repo.BillBatchSettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Smoke tests for retry-count persistence and DLQ escalation.
 *
 * <p>The retry counter was previously incremented only inside the emitted
 * BillBatchRetryEvent and never written back to the settlement row. On reload
 * getRetryCount() was unchanged, so the MAX_RETRIES guard could never advance and
 * a permanently failing batch retried without ever reaching the dead-letter
 * queue. These tests pin the persistence and the escalation boundary.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementProcessorRetryTest {

    private static final int MAX_RETRIES = 3;

    @Mock private BillBatchSettlementRepository settlementRepo;
    @Mock private Pain001Builder pain001Builder;
    @Mock private Central1Client central1Client;
    @Mock private SettlementEventPublisher eventPublisher;

    @InjectMocks private SettlementProcessor processor;

    private BillBatchSettlement settlementWithRetryCount(UUID batchId, int retryCount) {
        BillBatchSettlement s = new BillBatchSettlement();
        s.setBatchId(batchId);
        s.setRetryCount(retryCount);
        s.setStatus(SettlementStatus.READY);
        s.setPain001FileName("pain001-" + batchId + ".xml");
        return s;
    }

    private void givenUploadFails(UUID batchId, BillBatchSettlement existing) {
        when(settlementRepo.findByBatchId(batchId)).thenReturn(Optional.of(existing));
        when(pain001Builder.buildFileForBatch(any())).thenReturn("pain001.xml");
        when(central1Client.upload(anyString()))
                .thenThrow(new IllegalStateException("simulated Central1 failure"));
    }

    @Test
    @DisplayName("a failed upload persists the incremented retry count")
    void failedUploadPersistsIncrementedRetryCount() {
        UUID batchId = UUID.randomUUID();
        BillBatchSettlement existing = settlementWithRetryCount(batchId, 0);
        givenUploadFails(batchId, existing);

        processor.processNewBatch(new BillBatchReadyEvent(
                batchId, 1, new java.math.BigDecimal("250.00"),
                java.time.LocalDate.now(), "CAD", java.time.OffsetDateTime.now()));

        ArgumentCaptor<BillBatchSettlement> saved =
                ArgumentCaptor.forClass(BillBatchSettlement.class);
        verify(settlementRepo, atLeastOnce()).save(saved.capture());

        // The counter must be durable, not only present on the emitted event.
        assertThat(saved.getAllValues())
                .extracting(BillBatchSettlement::getRetryCount)
                .contains(1);
        assertThat(existing.getRetryCount()).isEqualTo(1);

        verify(eventPublisher).publishBatchRetry(any(BillBatchRetryEvent.class));
        verify(eventPublisher, never()).publishDlq(any(), anyString());
    }

    @Test
    @DisplayName("retries below the limit schedule another attempt rather than escalating")
    void belowLimitSchedulesRetry() {
        UUID batchId = UUID.randomUUID();
        BillBatchSettlement existing = settlementWithRetryCount(batchId, MAX_RETRIES - 1);
        givenUploadFails(batchId, existing);

        processor.retryBatch(new BillBatchRetryEvent(
                batchId, MAX_RETRIES - 1, "previous failure", java.time.OffsetDateTime.now()));

        assertThat(existing.getRetryCount()).isEqualTo(MAX_RETRIES);
        verify(eventPublisher).publishBatchRetry(any(BillBatchRetryEvent.class));
        verify(eventPublisher, never()).publishDlq(any(), anyString());
    }

    @Test
    @DisplayName("once MAX_RETRIES is reached the batch escalates to the DLQ and stops retrying")
    void atLimitEscalatesToDlq() {
        UUID batchId = UUID.randomUUID();
        BillBatchSettlement existing = settlementWithRetryCount(batchId, MAX_RETRIES);
        givenUploadFails(batchId, existing);

        processor.retryBatch(new BillBatchRetryEvent(
                batchId, MAX_RETRIES, "previous failure", java.time.OffsetDateTime.now()));

        verify(eventPublisher).publishDlq(eq(batchId), anyString());
        verify(eventPublisher, never()).publishBatchRetry(any(BillBatchRetryEvent.class));

        // The counter does not run past the limit, so escalation is terminal.
        assertThat(existing.getRetryCount()).isEqualTo(MAX_RETRIES);
        assertThat(existing.getStatus()).isEqualTo(SettlementStatus.FAILED);
    }
}
