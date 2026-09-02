package com.digitalbank.aicommerce.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.account.dto.AccountResponse;
import com.digitalbank.aicommerce.service.AccountQueryService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only account access for the agent.
 *
 * <p>No language model is involved on this path. It exists so the plumbing the
 * agent depends on - token relay through this service, downstream authorization
 * and the audit trail - can be verified on its own.</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentAccountController {

    private final AccountQueryService accountQueryService;

    /**
     * Accounts belonging to the authenticated caller. Takes no parameters by
     * design: the customer is read from the token.
     */
    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('SCOPE_fdx:accounts.read')")
    public ResponseEntity<List<AccountResponse>> myAccounts() {
        return ResponseEntity.ok(accountQueryService.myAccounts());
    }
}
