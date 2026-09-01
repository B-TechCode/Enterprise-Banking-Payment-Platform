package com.digitalbank.aicommerce.service;

import org.springframework.stereotype.Service;

/**
 * Records every tool invocation: who called, which tool, the input, the outcome
 * and the downstream result. Denied and failed calls are recorded too, since a
 * rejected attempt is the most interesting entry in the log.
 */
@Service
public class AuditService {

    // TODO: record one AgentActionLog per tool call.
}
