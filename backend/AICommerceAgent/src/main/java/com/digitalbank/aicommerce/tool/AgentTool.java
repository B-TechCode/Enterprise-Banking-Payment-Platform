package com.digitalbank.aicommerce.tool;

import java.util.Map;

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

    /** What the tool does, written for the model rather than for a developer. */
    String description();

    /** JSON schema for the arguments, in the subset the Gemini API accepts. */
    Map<String, Object> inputSchema();

    /**
     * Runs the tool and returns a result to hand back to the model.
     *
     * @param arguments      arguments as the model supplied them; untrusted
     * @param conversationId groups the resulting audit entries with the turn
     */
    Map<String, Object> execute(Map<String, Object> arguments, String conversationId);
}
