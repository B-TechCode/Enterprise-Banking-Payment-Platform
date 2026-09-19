package com.digitalbank.aicommerce.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry is the boundary that decides what the model can reach, so the
 * cases worth pinning are the refusals.
 */
class ToolRegistryTest {

    /** Stands in for any tool; only the name matters to the registry. */
    private record StubTool(String name) implements AgentTool {

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "OBJECT", "properties", Map.of());
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> arguments, String conversationId) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("registers the allowlisted tools and declares them to the model")
    void registersAllowlistedTools() {

        ToolRegistry registry = new ToolRegistry(List.of(
                new StubTool("get_my_accounts"),
                new StubTool("get_my_billers"),
                new StubTool("propose_bill_payment")));
        registry.register();

        assertThat(registry.find("get_my_accounts")).isPresent();
        assertThat(registry.find("get_my_billers")).isPresent();
        assertThat(registry.find("propose_bill_payment")).isPresent();

        assertThat(registry.declarations())
                .extracting(declaration -> declaration.name())
                .containsExactlyInAnyOrder(
                        "get_my_accounts", "get_my_billers", "propose_bill_payment");
    }

    @Test
    @DisplayName("refuses to start when a tool outside the allowlist is on the classpath")
    void rejectsToolOutsideAllowlist() {

        // A tool that executes rather than proposes must break the context, not
        // quietly become something the model can call.
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("execute_bill_payment")));

        assertThatThrownBy(registry::register)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execute_bill_payment")
                .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("an unknown tool name resolves to nothing, so the call can be refused")
    void unknownNameIsNotResolved() {

        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("get_my_accounts")));
        registry.register();

        assertThat(registry.find("transfer_funds")).isEmpty();
        assertThat(registry.find("confirm_bill_payment")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }
}
