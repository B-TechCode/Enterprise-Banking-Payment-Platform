package com.digitalbank.aicommerce.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversational entry point.
 *
 * <p>Accepts a natural-language message and returns the reply of the agent. When
 * the request implies a payment, the reply carries a proposal for the user to
 * confirm; no payment is created on this path.</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentChatController {

    // TODO: POST /chat
}
