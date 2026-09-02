package com.digitalbank.aicommerce.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.account.dto.AccountResponse;
import com.digitalbank.aicommerce.client.AccountFeignClient;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;

import lombok.RequiredArgsConstructor;

/**
 * Reads the accounts of the calling customer through Account Service.
 *
 * <p>The customer id comes from the token, never from a request parameter, so a
 * caller cannot ask for the accounts of someone else. Account Service enforces
 * the same rule independently on the relayed token.</p>
 */
@Service
@RequiredArgsConstructor
public class AccountQueryService {

    private static final String TOOL_NAME = "get_my_accounts";

    private final AccountFeignClient accountClient;
    private final CallerIdentity callerIdentity;
    private final AuditService auditService;

    public List<AccountResponse> myAccounts() {

        String customerId;
        AgentActionLog entry;

        try {
            customerId = callerIdentity.requireCustomerId();
        } catch (RuntimeException denied) {
            auditService.record(
                    AgentActionLog.starting(TOOL_NAME, null, null),
                    ActionOutcome.DENIED,
                    denied.getMessage(),
                    null);
            throw denied;
        }

        entry = AgentActionLog.starting(TOOL_NAME, customerId, callerIdentity.subject());

        try {
            List<AccountResponse> accounts = accountClient.findAccountsByCustomer(customerId);

            auditService.record(entry, ActionOutcome.SUCCESS,
                    "returned " + accounts.size() + " account(s)", 200);

            return accounts;

        } catch (RuntimeException ex) {
            auditService.record(entry, ActionOutcome.ERROR, ex.getMessage(), null);
            throw ex;
        }
    }
}
