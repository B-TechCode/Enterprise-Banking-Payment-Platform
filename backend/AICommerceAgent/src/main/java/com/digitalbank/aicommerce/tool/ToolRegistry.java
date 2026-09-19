package com.digitalbank.aicommerce.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.digitalbank.aicommerce.llm.GeminiFunctionDeclaration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The allowlist of tools the model may call.
 *
 * <p>A tool name that is not registered is a hard failure and an audit entry,
 * never a passthrough.</p>
 *
 * <p>Membership is not decided by what happens to be on the classpath. The set
 * below is the authority, and a bean implementing {@link AgentTool} that is not
 * named in it fails the context at startup rather than quietly becoming
 * callable. Adding a capability is therefore an explicit edit here, which is the
 * point: this slice grants read access to accounts and nothing else.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRegistry {

    /**
     * The only tools that may ever be offered to the model.
     *
     * <p>Note what is absent: nothing here executes a payment. The payment tool
     * stages a proposal and stops. Execution lives behind a separate endpoint
     * that the model is not part of.</p>
     */
    private static final Set<String> ALLOWED = Set.of(
            "get_my_accounts",
            "get_my_billers",
            "propose_bill_payment");

    private final List<AgentTool> discoveredTools;

    private final Map<String, AgentTool> byName = new LinkedHashMap<>();

    @PostConstruct
    void register() {

        for (AgentTool tool : discoveredTools) {

            if (!ALLOWED.contains(tool.name())) {
                throw new IllegalStateException(
                        "Tool '" + tool.name() + "' (" + tool.getClass().getName()
                                + ") is not in the agent allowlist. Add it deliberately "
                                + "or remove the bean; it will not be registered.");
            }

            AgentTool previous = byName.put(tool.name(), tool);

            if (previous != null) {
                throw new IllegalStateException(
                        "Two tools are registered under the name '" + tool.name() + "'");
            }
        }

        log.info("agent tool allowlist active: {}", byName.keySet());
    }

    /** Resolves a name the model produced. Empty means refuse the call. */
    public Optional<AgentTool> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /** Declarations for the request, in registration order. */
    public List<GeminiFunctionDeclaration> declarations() {
        return byName.values().stream()
                .map(tool -> new GeminiFunctionDeclaration(
                        tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }
}
