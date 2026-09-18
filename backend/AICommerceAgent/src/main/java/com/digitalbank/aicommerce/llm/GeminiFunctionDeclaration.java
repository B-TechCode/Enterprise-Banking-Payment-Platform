package com.digitalbank.aicommerce.llm;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool as described to the model: its name, what it does, and the shape of its
 * input as a JSON schema.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiFunctionDeclaration(
        String name,
        String description,
        Map<String, Object> parameters) {
}
