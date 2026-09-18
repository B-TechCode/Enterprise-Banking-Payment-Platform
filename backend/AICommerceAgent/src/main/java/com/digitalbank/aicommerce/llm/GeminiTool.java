package com.digitalbank.aicommerce.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The tool block of a request. Everything the model is allowed to call must
 * appear here; anything absent cannot be invoked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiTool(
        List<GeminiFunctionDeclaration> functionDeclarations) {
}
