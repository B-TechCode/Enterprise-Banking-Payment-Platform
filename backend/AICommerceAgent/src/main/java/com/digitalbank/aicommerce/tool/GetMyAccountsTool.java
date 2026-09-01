package com.digitalbank.aicommerce.tool;

import org.springframework.stereotype.Component;

/**
 * Read-only. Returns the accounts of the caller, with balances.
 *
 * <p>The customer id comes from the token of the caller, never from model
 * output, so the model cannot name the account of another customer even if it
 * tries.</p>
 */
@Component
public class GetMyAccountsTool implements AgentTool {

    @Override
    public String name() {
        return "get_my_accounts";
    }

    // TODO: call Account Service and map the response for the model.
}
