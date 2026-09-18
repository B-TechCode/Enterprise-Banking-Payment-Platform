package com.digitalbank.aicommerce.llm;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One piece of a message: either text, a request to call a function, or the
 * result of one. Exactly one of the three is set.
 *
 * <p>{@code thoughtSignature} is an opaque token the model attaches to a
 * function call. It must be sent back unchanged with the conversation history or
 * the model loses the reasoning behind its own call, so it is carried through
 * this record rather than dropped.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiPart(
        String text,
        GeminiFunctionCall functionCall,
        GeminiFunctionResponse functionResponse,
        String thoughtSignature) {

    public static GeminiPart ofText(String text) {
        return new GeminiPart(text, null, null, null);
    }

    /** The result of a tool, addressed to the call it answers. */
    public static GeminiPart ofFunctionResponse(String id, String name, Map<String, Object> response) {
        return new GeminiPart(null, null, new GeminiFunctionResponse(id, name, response), null);
    }

    /**
     * Deliberately not named {@code isFunctionCall}. Jackson treats an
     * {@code is}-prefixed method as the accessor for a property of that name,
     * which would both write a bogus {@code functionCall} boolean onto every text
     * part and, if silenced with {@code @JsonIgnore}, suppress the real field on
     * the way in. A name Jackson does not recognise avoids the collision outright.
     */
    public boolean hasFunctionCall() {
        return functionCall != null;
    }
}
