package com.payments.orch.controller;

import com.payments.orch.dto.BillPayRequest;
import com.payments.orch.dto.PaymentAcceptedResponse;
import com.payments.orch.service.BillPayOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/*
 * SECURITY FINDING - OPEN, deliberately not fixed yet (found during AI agent Slice 3).
 *
 * No endpoint in this controller carries @PreAuthorize, and this module defines
 * no scope rules of its own. The only control applied is the shared
 * commons-security chain (DefaultSecurityConfig), which requires an
 * authenticated JWT and nothing more.
 *
 * Consequence: any valid token - including a client-credentials token, or a user
 * token that lacks fdx:bill.write - can initiate a bill payment. The API gateway
 * routes /payments/** here, so this is reachable externally as
 * POST /payments/payments/billpay.
 *
 * This also means the AI agent's confirm endpoint
 * (POST /api/v1/agent/payments/{id}/confirm, which requires
 * SCOPE_fdx:bill.write plus proposal ownership and lifecycle checks) is not the
 * only route to a bill payment: a caller can skip it and come here directly.
 * The agent's own invariant - model output can never reach this API - is
 * unaffected; the gap is in platform authorization, not in the agent.
 *
 * Not verified: whether BillPayOrchestrator checks that debtorAccountId belongs
 * to the caller. If it does not, this is also an IDOR on the debtor account,
 * the same defect class fixed in Account Service in commit 6feb81e.
 *
 * Suggested fix: @PreAuthorize("hasAuthority('SCOPE_fdx:bill.write')") on
 * billPay, and on get() at least a read scope plus an ownership check.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

  private final BillPayOrchestrator billPayOrchestrator;

  @PostMapping("/payments/billpay")
  public ResponseEntity<PaymentAcceptedResponse> billPay(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody BillPayRequest req
  ) {
    var res = billPayOrchestrator.acceptBillPay(req, idempotencyKey);
    var location = URI.create("/api/v1/payments/" + res.paymentId());
    return ResponseEntity.accepted().location(location).body(res);
  }

  @GetMapping("/payments/{paymentId}")
  public ResponseEntity<?> get(@PathVariable UUID paymentId) {
    return ResponseEntity.ok(billPayOrchestrator.view(paymentId));
  }
}
