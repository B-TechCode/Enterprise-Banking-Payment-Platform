package com.digitalbank.aicommerce.llm;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A generateContent request.
 *
 * <p>The system instruction is sent as its own field rather than as a first user
 * message, so a later user message cannot be mistaken for it.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(
        GeminiContent systemInstruction,
        List<GeminiContent> contents,
        List<GeminiTool> tools,
        Map<String, Object> generationConfig) {
}
