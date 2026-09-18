package com.digitalbank.aicommerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A natural-language message from the user, with the conversation it belongs to.
 *
 * <p>The message is bounded so a caller cannot push an unbounded body through to
 * the model. The conversation id is optional; one is generated when it is absent.</p>
 */
public record ChatRequest(

        @Size(max = 64, message = "conversationId must be at most 64 characters")
        String conversationId,

        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message must be at most 2000 characters")
        String message) {
}
