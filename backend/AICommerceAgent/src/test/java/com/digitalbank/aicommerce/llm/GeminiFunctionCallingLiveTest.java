package com.digitalbank.aicommerce.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.digitalbank.aicommerce.config.AgentProperties;
import com.digitalbank.aicommerce.config.GeminiClientConfig;

/**
 * Exercises the real Gemini API to prove the request and response shapes in this
 * package are right.
 *
 * <p>Runs only when GEMINI_API_KEY is present, so a build without a key still
 * passes. It needs no database, no Eureka and no Auth0 token: the point is the
 * wire contract with the model, not the banking stack around it.</p>
 *
 * <p>What it is really guarding is the round trip of the thought signature the
 * model attaches to a function call. Dropping it does not fail loudly; the model
 * simply loses the reasoning behind its own call, which is the kind of defect
 * that would otherwise surface as an occasional wrong answer in production.</p>
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiFunctionCallingLiveTest {

    private GeminiClient client;
    private List<GeminiFunctionDeclaration> tools;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();

        client = new GeminiClient(
                new GeminiClientConfig().geminiRestClient(properties),
                properties);

        tools = List.of(new GeminiFunctionDeclaration(
                "get_my_accounts",
                "Returns the bank accounts belonging to the user you are speaking with, "
                        + "including balance and currency. Takes no arguments.",
                Map.of("type", "OBJECT", "properties", Map.of())));
    }

    @Test
    @DisplayName("asks for the account tool, then answers from the result it was given")
    void completesAToolLoop() {

        GeminiContent system = GeminiContent.user(
                "You are a banking assistant. Use get_my_accounts to answer questions "
                        + "about balances. Report only figures the tool returned.");

        List<GeminiContent> conversation = new ArrayList<>();
        conversation.add(GeminiContent.user("How much is in my savings account?"));

        // First exchange: the model should ask for the tool rather than answer.
        GeminiResponse first = client.generate(system, conversation, tools);
        GeminiContent modelTurn = first.firstContent();

        assertThat(modelTurn).isNotNull();

        GeminiPart callPart = modelTurn.parts().stream()
                .filter(GeminiPart::hasFunctionCall)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "model did not request a tool call; parts were " + modelTurn.parts()));

        assertThat(callPart.functionCall().name()).isEqualTo("get_my_accounts");

        // The signature must have survived deserialization, or it cannot be sent
        // back on the next request.
        assertThat(callPart.thoughtSignature())
                .as("thought signature must be captured for the round trip")
                .isNotBlank();

        // Second exchange: hand back a known result and check the model uses it.
        conversation.add(modelTurn);
        conversation.add(GeminiContent.toolResults(List.of(
                GeminiPart.ofFunctionResponse(
                        callPart.functionCall().id(),
                        "get_my_accounts",
                        Map.of("accounts", List.of(Map.of(
                                "displayName", "Everyday Savings",
                                "accountType", "SAVINGS",
                                "maskedAccountNumber", "****4417",
                                "balance", "2750.25",
                                "currency", "USD")))))));

        GeminiResponse second = client.generate(system, conversation, tools);
        GeminiContent answer = second.firstContent();

        assertThat(answer).isNotNull();

        String text = answer.parts().stream()
                .map(GeminiPart::text)
                .filter(part -> part != null && !part.isBlank())
                .reduce("", String::concat);

        assertThat(text)
                .as("the reply should name the account the tool returned")
                .contains("Everyday Savings");

        // Thousands separators are the model's own formatting choice and not
        // something to pin, so the figure is compared without them.
        assertThat(text.replace(",", ""))
                .as("the reply should quote the balance the tool returned")
                .contains("2750.25");
    }
}
