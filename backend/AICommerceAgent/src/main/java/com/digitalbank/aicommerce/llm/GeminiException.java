package com.digitalbank.aicommerce.llm;

/**
 * The model could not be reached, or answered with something unusable.
 *
 * <p>Carries a message safe to show a user: upstream error bodies are logged,
 * not propagated.</p>
 */
public class GeminiException extends RuntimeException {

    public GeminiException(String message) {
        super(message);
    }

    public GeminiException(String message, Throwable cause) {
        super(message, cause);
    }
}
