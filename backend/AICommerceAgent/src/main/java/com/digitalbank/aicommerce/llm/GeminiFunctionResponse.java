package com.digitalbank.aicommerce.llm;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result handed back to the model after a tool ran.
 *
 * <p>The id echoes the call being answered, so the model can line a result up
 * with its request when more than one call is outstanding.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiFunctionResponse(
        String id,
        String name,
        Map<String, Object> response) {
}
