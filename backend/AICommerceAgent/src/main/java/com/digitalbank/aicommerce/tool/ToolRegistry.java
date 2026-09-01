package com.digitalbank.aicommerce.tool;

import org.springframework.stereotype.Component;

/**
 * The allowlist of tools the model may call.
 *
 * <p>A tool name that is not registered is a hard failure and an audit entry,
 * never a passthrough.</p>
 */
@Component
public class ToolRegistry {

    // TODO: resolve a tool by name; expose declarations for the request.
}
