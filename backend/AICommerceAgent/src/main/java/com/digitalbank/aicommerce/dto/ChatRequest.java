package com.digitalbank.aicommerce.dto;

/**
 * A natural-language message from the user, with the conversation it belongs to.
 */
public record ChatRequest(String conversationId, String message) {
}
