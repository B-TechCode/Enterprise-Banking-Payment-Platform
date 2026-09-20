package com.payments.orch.controller;

import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.dto.PaymentAcceptedResponse;
import com.payments.orch.service.BillPayOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/*
 * These endpoints are reachable from outside: the API gateway routes
 * /payments/** here, so POST /payments/payments/billpay is a public entry
 * point to moving money.
 *
 * They once carried no authorization of their own. The only control was the
 * shared commons-security chain, which requires a valid JWT and nothing more,
 * so any authenticated token reached them, and GET returned any payment to any
 * caller. Each endpoint now states the scope it needs, and a payment is
 * readable only by the customer who asked for it.
 *
 * Which account may be paid from is decided further in rather than here: the
 * hold is placed with the caller's own token, and Account Service refuses an
 * account they do not own. PaymentAuthorizationWiringTest keeps that call on
 * the relaying client, because making it with the service token would remove
 * that check.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

  private final BillPayOrchestrator billPayOrchestrator;

  /**
   * Initiates a bill payment.
   *
   * <p>The scope is what stops any authenticated token from reaching this at
   * all. Which account may be paid from is decided further in: the hold is
   * placed with the caller's own token and Account Service refuses an account
   * they do not own.</p>
   */
  @PostMapping("/payments/billpay")
  @PreAuthorize("hasAuthority('SCOPE_fdx:bill.write')")
  public ResponseEntity<PaymentAcceptedResponse> billPay(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody BillPayRequest req
  ) {
    var res = billPayOrchestrator.acceptBillPay(req, idempotencyKey);
    var location = URI.create("/api/v1/payments/" + res.paymentId());
    return ResponseEntity.accepted().location(location).body(res);
  }

  /**
   * A payment, readable by the customer who asked for it. Someone else's
   * payment is reported as missing rather than refused, so this cannot be used
   * to discover which payment ids exist.
   */
  @GetMapping("/payments/{paymentId}")
  @PreAuthorize("hasAuthority('SCOPE_fdx:bill.read')")
  public ResponseEntity<?> get(@PathVariable UUID paymentId) {
    return ResponseEntity.ok(billPayOrchestrator.view(paymentId));
  }
}
