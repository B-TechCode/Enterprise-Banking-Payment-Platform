package com.digitalbank.aicommerce.llm;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.digitalbank.aicommerce.config.AgentProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transport for the Gemini generateContent endpoint. Holds no conversation state
 * and makes no decisions: the loop lives in the orchestrator.
 *
 * <p>Nothing about the caller's identity is sent here. The bearer token stays in
 * this service and is used only for downstream banking calls, so the model never
 * receives a credential it could echo back.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    private final RestClient geminiRestClient;
    private final AgentProperties properties;

    public GeminiResponse generate(GeminiContent systemInstruction,
                                   List<GeminiContent> conversation,
                                   List<GeminiFunctionDeclaration> tools) {

        GeminiRequest request = new GeminiRequest(
                systemInstruction,
                conversation,
                tools.isEmpty() ? null : List.of(new GeminiTool(tools)),
                Map.of("temperature", properties.getTemperature()));

        try {
            GeminiResponse response = geminiRestClient.post()
                    .uri("/models/{model}:generateContent", properties.getModel())
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse.class);

            if (response == null) {
                throw new GeminiException("Gemini returned an empty response body");
            }

            return response;

        } catch (RestClientResponseException ex) {
            // The body of an error can contain the request that caused it, so it
            // is logged rather than propagated to the user.
            log.error("Gemini call failed status={} body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());

            throw new GeminiException(
                    "The assistant is unavailable right now (upstream status "
                            + ex.getStatusCode().value() + ")", ex);
        }
    }
}
