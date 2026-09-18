package com.digitalbank.aicommerce.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.account.dto.AccountResponse;
import com.digitalbank.aicommerce.service.AccountQueryService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only. Returns the accounts of the caller, with balances.
 *
 * <p>The customer id comes from the token of the caller, never from model
 * output, so the model cannot name the account of another customer even if it
 * tries. The tool accordingly takes no arguments at all: there is no parameter
 * through which a customer could be requested.</p>
 */
@Component
@RequiredArgsConstructor
public class GetMyAccountsTool implements AgentTool {

    private final AccountQueryService accountQueryService;

    @Override
    public String name() {
        return "get_my_accounts";
    }

    @Override
    public String description() {
        return "Returns the bank accounts belonging to the user you are speaking with, "
                + "including the current balance and currency of each. Takes no arguments: "
                + "the user's identity is established by their session, not by you. "
                + "Call this whenever you need account or balance information.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, String conversationId) {

        List<AccountResponse> accounts = accountQueryService.myAccounts(conversationId);

        // Only the fields needed to answer a balance question are exposed. The
        // full account number and the internal id stay in this service: the
        // model is given the masked form, which is what it should be repeating
        // back to the user anyway.
        List<Map<String, Object>> view = accounts.stream()
                .map(account -> Map.<String, Object>of(
                        "accountId", String.valueOf(account.id()),
                        "displayName", nullSafe(account.displayName()),
                        "accountType", String.valueOf(account.accountType()),
                        "status", String.valueOf(account.status()),
                        "maskedAccountNumber", nullSafe(account.maskedAccountNumber()),
                        "balance", String.valueOf(account.balance()),
                        "currency", nullSafe(account.currency())))
                .toList();

        return Map.of("accounts", view);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
