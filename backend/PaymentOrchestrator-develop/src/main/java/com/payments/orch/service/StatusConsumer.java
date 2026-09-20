package com.payments.orch.service;

import com.account.dto.PostingRequest;
import com.events.billpay.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.client.AccountClient;
import com.payments.orch.client.AccountM2MClient;
import com.payments.orch.domain.Payment;
import com.payments.orch.domain.PaymentState;
import com.payments.orch.domain.ProcessedEvent;
import com.events.billpay.BillpayStatusEvent;
import com.payments.orch.repo.PaymentRepo;
import com.payments.orch.repo.ProcessedEventRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatusConsumer {
  private final PaymentRepo paymentRepo;
  private final ProcessedEventRepo processed;
  private final ObjectMapper om;
  private final AccountM2MClient accountM2MClient;


  @KafkaListener(topics="billpay.status", groupId="payment-api")
  @Transactional
  public void onMessage(String message) throws Exception {
    var evt = om.readValue(message, BillpayStatusEvent.class);
    if (processed.existsByHandlerAndEventId("status", evt.eventId().toString())) return;

    Payment p = paymentRepo.findById((evt.paymentId())).orElse(null);
    if (p == null) return;

    PaymentState target = "POSTED".equalsIgnoreCase(evt.status())
        ? PaymentState.POSTED
        : PaymentState.FAILED;

    // Deduplicating by event id is not enough on its own. Settlement gives every
    // status event a fresh id, so one confirmation handled twice arrives as two
    // events with different ids. Without this check the second POSTED debited
    // the account again, and a late FAILED marked a debited payment as failed.
    // A finished payment is therefore never touched: no Account Service call,
    // no state change. The event is still recorded, so it is not redelivered.
    if (!p.getState().canMoveTo(target)) {
      if (p.getState() != target) {
        // POSTED after FAILED, or FAILED after POSTED: the bank's two answers
        // contradict each other. Not something to resolve automatically.
        log.error("Conflicting status {} for payment {} already {}; needs manual reconciliation",
            target, p.getPaymentId(), p.getState());
      } else {
        log.info("Duplicate status {} for payment {} ignored", target, p.getPaymentId());
      }
      markProcessed(evt);
      return;
    }

    // The payment id keys both calls: it is the same for every redelivery of
    // this payment's confirmation, and unique to the payment. Account Service
    // applies each posting once, however often this runs. The guard above
    // catches a duplicate the orchestrator can see; this catches the one it
    // cannot, where the debit succeeded and the local transaction then rolled
    // back, leaving nothing here to show it had happened.
    // Keys are unique per account, so each posting needs its own: a shared key
    // would let the first claim it and the second be skipped as already
    // applied. Both are derived from the payment id, which is the same for
    // every redelivery of this payment's confirmation.
    String captureKey = p.getPaymentId() + ":capture";
    String releaseKey = p.getPaymentId() + ":release";

    if (target == PaymentState.POSTED) {
    	// One call: the held funds become a debit without being released first.
    	// Releasing and then debiting left them spendable in between, and a
    	// debit that then failed for insufficient funds left the payment
    	// uncollectable with its reservation already gone.
    	accountM2MClient.captureHold(p.getDebtorAccountId(), p.getPaymentId(), captureKey);
    	p.setState(PaymentState.POSTED);
    } else {
    	// Nothing is owed, so the reservation is simply given up.
    	accountM2MClient.releaseHold(p.getDebtorAccountId(), p.getPaymentId(), releaseKey);
      p.setState(PaymentState.FAILED);
    }
   // p.setExternalStatusCode(evt.status());
    p.setReason(evt.reason());
    p.setUpdatedAt(OffsetDateTime.now());
    paymentRepo.save(p);

    markProcessed(evt);
  }

  private void markProcessed(BillpayStatusEvent evt) {
    processed.save(ProcessedEvent.builder()
        .handler("status").eventId(evt.eventId().toString())
        .processedAt(OffsetDateTime.now()).build());
  }
}
