package com.digitalbank.aicommerce.tool;

/**
 * A capability the model may invoke.
 *
 * <p>Implementations call the public REST APIs of the platform as the
 * authenticated caller. No implementation may touch a database, and none may
 * move money: the payment tool only stages a proposal for a human to confirm.</p>
 */
public interface AgentTool {

    /** Tool name exactly as declared to the model. */
    String name();

    // TODO: description(), inputSchema(), execute(input).
}
