package com.digitalbank.aicommerce.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A generateContent response.
 *
 * <p>Unknown fields are ignored: the API adds them over time, and an agent that
 * fails to deserialize on a new field would break without any change here.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(
        List<Candidate> candidates,
        PromptFeedback promptFeedback) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            GeminiContent content,
            String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptFeedback(String blockReason) {
    }

    /** The model's message, or null when the response carried no candidate. */
    public GeminiContent firstContent() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0).content();
    }
}
