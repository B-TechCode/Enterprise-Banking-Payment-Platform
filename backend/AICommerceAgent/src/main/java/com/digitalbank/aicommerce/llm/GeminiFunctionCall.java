package com.digitalbank.aicommerce.llm;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool call requested by the model.
 *
 * <p>The name is untrusted input. It is matched against the registry and
 * refused if it is not there, never dispatched on trust.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiFunctionCall(
        String id,
        String name,
        Map<String, Object> args) {
}
