package com.payments.orch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.dto.PaymentAcceptedResponse;
import com.payments.orch.client.AccountClient;
import com.payments.orch.domain.Outbox;
import com.payments.orch.domain.Payment;
import com.payments.orch.domain.PaymentState;
import com.events.billpay.*;
import com.payments.orch.repo.OutboxRepo;
import com.payments.orch.repo.PaymentRepo;
import com.account.dto.CreateHoldRequest;
import com.commons.exception.ResourceNotFoundException;
import com.commons.security.CurrentUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillPayOrchestrator {

  private final BillPayValidator validator;
  private final AccountClient accounts;
  private final PaymentRepo paymentRepo;
  private final OutboxRepo outboxRepo;
  private final ObjectMapper om;
  private final CurrentUser currentUser;

  @Transactional
  public PaymentAcceptedResponse acceptBillPay(BillPayRequest req, String idemKey) {
    // 1) Idempotency: check if this payment already exists
    var existing = paymentRepo.findByIdempotencyKey(idemKey);
    if (existing.isPresent()) {
      var p = existing.get();
      return new PaymentAcceptedResponse(
          p.getPaymentId(),
          p.getState().name(),
          "/api/v1/payments/" + p.getPaymentId()
      );
    }
    // 2) Business validation (account, biller, execution date, etc.)
    validator.validate(req);


    // 3) Build CreateHoldRequest for AccountService

    var holdReq = new CreateHoldRequest(
            req.amount().value(),     // BigDecimal
            "BILLPAY",                // reason
            null,                     // releaseAt (optional)
            idemKey                   // idempotency key
    );
    

    // 4) Call AccountService to place hold (JWT relay via FeignTokenRelayConfig).
    //
    // The hold is placed with the caller's own token, which is what confines a
    // payment to the caller's own accounts: Account Service refuses an account
    // they do not own. Using the service token here would remove that check.
    //
    // The payment id IS the hold id. Releasing and capturing both rely on it:
    // StatusConsumer passes the payment id where a hold id is expected. Nothing
    // enforces that beyond this line, so it is asserted in
    // BillPayOrchestratorIdempotencyTest.
    var paymentId=  accounts.placeHold(req.debtorAccountId(), idemKey, holdReq).holdId();

    // 5) Persist Payment row in FUNDS_HELD state
    var now = OffsetDateTime.now();
    var payment = Payment.builder()
        .paymentId(paymentId)
        .state(PaymentState.FUNDS_HELD)
        .debtorAccountId(req.debtorAccountId())
        .billerRefNumber(req.billerReferenceNumber())
        .invoiceReference(req.invoiceReference())
        .executionDate(LocalDate.parse(req.executionDate()))
        .amountValue(req.amount().value())
        .amountCcy(req.amount().currency())
        .idempotencyKey(idemKey)
        // Recorded from the caller's token so the payment can be read back by
        // its owner and nobody else.
        .customerId(currentUser.customerIdClaim().orElse(null))
        .createdAt(now)
        .updatedAt(now)
        .build();
    paymentRepo.save(payment);

    // 6) Outbox event: billpay.requested
    var evt = BillPayRequested.builder()
        .eventId(UUID.randomUUID().toString())
        .paymentId(paymentId)
        .debtorAccountId(payment.getDebtorAccountId())
        .billerRefNumber(payment.getBillerRefNumber())
        .invoiceReference(payment.getInvoiceReference())
        .executionDate(payment.getExecutionDate().toString())
        .amountValue(payment.getAmountValue())
        .amountCcy(payment.getAmountCcy())
        .occurredAt(now.toString())
        .schemaVersion("1")
        .channel("billpay")
        .build();

    outboxRepo.save(Outbox.builder()
        .topic("billpay.requested")
        .key(paymentId)
        .payloadJson(write(evt))
        .state("PENDING")
        .createdAt(now)
        .updatedAt(now)
        .build());

    // 7) Return async-202 style response
    return new PaymentAcceptedResponse(
        paymentId,
        PaymentState.FUNDS_HELD.name(),
        "/api/v1/payments/" + paymentId
    );
  }

  /**
   * A payment, readable by the customer who asked for it.
   *
   * <p>A payment belonging to someone else is reported as missing rather than
   * refused, so this cannot be used to discover which payment ids exist. Until
   * this check existed, any authenticated caller could read any payment: its
   * amount, biller, invoice reference and debtor account.</p>
   */
  public Payment view(UUID paymentId) {
    Payment payment = paymentRepo.findById(paymentId)
        .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

    if (!belongsToCaller(payment)) {
      throw new ResourceNotFoundException("Payment not found");
    }

    return payment;
  }

  private boolean belongsToCaller(Payment payment) {
    String caller = currentUser.customerIdClaim().orElse(null);
    if (caller == null) {
      // A token with no customer identity: a service, or a user token missing
      // the claim. Neither is the owner of a payment.
      return false;
    }

    if (payment.getCustomerId() != null) {
      return caller.equals(payment.getCustomerId());
    }

    // Accepted before the customer was recorded on the payment. Ask Account
    // Service who owns the debtor account, with the caller's own token: it
    // refuses an account they do not own, which answers the question either
    // way. These rows disappear as old payments age out.
    try {
      var owner = accounts.getOwner(payment.getDebtorAccountId());
      return owner != null && caller.equals(owner.getCustomerId());
    } catch (Exception denied) {
      log.info("Owner lookup refused for legacy payment {}: {}",
          payment.getPaymentId(), denied.getMessage());
      return false;
    }
  }

  private String write(Object o) {
    try {
      return om.writeValueAsString(o);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
