package com.digitalbank.aicommerce.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digitalbank.aicommerce.dto.ChatRequest;
import com.digitalbank.aicommerce.dto.ChatResponse;
import com.digitalbank.aicommerce.service.AgentOrchestrator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Conversational entry point.
 *
 * <p>The scope required is the same one the direct account read requires. The
 * model does not widen what the caller can reach: a token that could not read an
 * account directly cannot read one by asking.</p>
 *
 * <p>No payment is created on this path. When the request implies one, the reply
 * will carry a proposal for the user to confirm; that arrives with the payment
 * slice, and until then the agent answers account questions only.</p>
 */
@RestController
@RequestMapping("/api/v1/agent/chat")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentOrchestrator orchestrator;

    /** Runs one conversation turn. */
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_fdx:accounts.read')")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(orchestrator.chat(request));
    }
}
