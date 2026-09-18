package com.digitalbank.aicommerce.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message in the conversation, attributed to "user" or "model".
 *
 * <p>Tool results are sent with the "user" role: on this API a function response
 * comes from the caller, not from the model.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiContent(
        String role,
        List<GeminiPart> parts) {

    public static GeminiContent user(String text) {
        return new GeminiContent("user", List.of(GeminiPart.ofText(text)));
    }

    public static GeminiContent toolResults(List<GeminiPart> parts) {
        return new GeminiContent("user", parts);
    }
}
