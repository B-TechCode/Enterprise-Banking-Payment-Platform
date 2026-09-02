package com.digitalbank.aicommerce.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversational entry point.
 *
 * <p>Arrives with the language model in the next slice. When the request implies
 * a payment, the reply will carry a proposal for the user to confirm; no payment
 * is created on this path.</p>
 */
@RestController
@RequestMapping("/api/v1/agent/chat")
public class AgentChatController {

    // TODO: POST / - runs one conversation turn.
}
