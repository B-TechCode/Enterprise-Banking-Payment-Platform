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
    @DisplayName("registers the allowlisted tool and declares it to the model")
    void registersAllowlistedTool() {

        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("get_my_accounts")));
        registry.register();

        assertThat(registry.find("get_my_accounts")).isPresent();
        assertThat(registry.declarations())
                .singleElement()
                .extracting(declaration -> declaration.name())
                .isEqualTo("get_my_accounts");
    }

    @Test
    @DisplayName("refuses to start when a tool outside the allowlist is on the classpath")
    void rejectsToolOutsideAllowlist() {

        // A payment tool that became a bean by accident must break the context,
        // not quietly become something the model can call.
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("propose_bill_payment")));

        assertThatThrownBy(registry::register)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("propose_bill_payment")
                .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("an unknown tool name resolves to nothing, so the call can be refused")
    void unknownNameIsNotResolved() {

        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("get_my_accounts")));
        registry.register();

        assertThat(registry.find("transfer_funds")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }
}
