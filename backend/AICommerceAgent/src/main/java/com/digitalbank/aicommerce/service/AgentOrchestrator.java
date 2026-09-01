package com.digitalbank.aicommerce.service;

import org.springframework.stereotype.Service;

/**
 * Drives the Claude tool-use loop by hand.
 *
 * <p>A manual loop is used rather than the SDK tool runner because the turn must
 * stop at a proposal and hand control back to a human across an HTTP boundary.
 * The loop also gives one interception point per tool call for the audit trail
 * and for rejecting any tool name that is not registered.</p>
 */
@Service
public class AgentOrchestrator {

    // TODO: run one conversation turn, dispatching tool calls via ToolRegistry.
}
