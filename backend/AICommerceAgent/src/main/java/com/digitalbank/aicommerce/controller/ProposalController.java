package com.digitalbank.aicommerce.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digitalbank.aicommerce.dto.PaymentConfirmationResponse;
import com.digitalbank.aicommerce.service.PaymentConfirmationService;

import lombok.RequiredArgsConstructor;

/**
 * Human confirmation boundary.
 *
 * <p>Confirmation is a request the user makes, not a tool the model can call.
 * The model is not invoked on this path at all: the payment is executed from the
 * stored proposal, so nothing said between proposing and confirming can change
 * the amount, the biller or the account.</p>
 *
 * <p>This class depends on {@code PaymentConfirmationService} alone. It has no reference to
 * the orchestrator, the Gemini client or any tool, and it takes no request body,
 * so there is nothing a model could populate even indirectly. The proposal id in
 * the path is the whole input.</p>
 *
 * <p>Known gap, not addressed in this slice: {@code PaymentController} in the
 * payment orchestrator carries no {@code @PreAuthorize} of its own and is routed
 * through the gateway, so any authenticated token can initiate a bill payment
 * there directly, bypassing this endpoint's scope, ownership and lifecycle
 * checks. The agent's invariant still holds - model output cannot reach the
 * payment API - but this endpoint is not the platform's only route to a payment.
 * Documented in full on {@code PaymentController}; it wants fixing there rather
 * than compensating for here.</p>
 */
@RestController
@RequestMapping("/api/v1/agent/payments")
@RequiredArgsConstructor
public class ProposalController {

    private final PaymentConfirmationService confirmationService;

    /**
     * Executes a staged proposal.
     *
     * <p>Returns 404 when the proposal does not exist or belongs to another
     * customer, 409 when it has expired or is no longer confirmable, and 502 when
     * the orchestrator could not accept it. A 502 leaves the proposal confirmed
     * and safe to retry: the payment carries the proposal id as its
     * Idempotency-Key.</p>
     */
    @PostMapping("/{proposalId}/confirm")
    @PreAuthorize("hasAuthority('SCOPE_fdx:bill.write')")
    public ResponseEntity<PaymentConfirmationResponse> confirm(
            @PathVariable("proposalId") UUID proposalId) {

        return ResponseEntity.ok(confirmationService.confirm(proposalId));
    }

    // TODO: POST /{proposalId}/reject
}
