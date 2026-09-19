package com.digitalbank.aicommerce.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import com.digitalbank.aicommerce.client.PaymentFeignClient;
import com.digitalbank.aicommerce.controller.AgentChatController;
import com.digitalbank.aicommerce.controller.ProposalController;
import com.digitalbank.aicommerce.llm.GeminiClient;
import com.digitalbank.aicommerce.service.AgentOrchestrator;

/**
 * The structural guarantee of the design: model output cannot reach the payment
 * API, and the payment path cannot reach the model.
 *
 * <p>The rule the whole agent rests on is that nothing the model produces can
 * cause money to move. That holds because {@link PaymentFeignClient} is reachable
 * only from the confirmation path, which no tool touches. A reviewer can check
 * that by reading the code today, but a later change could quietly break it:
 * injecting the client into a service a tool already depends on would be enough,
 * and nothing would fail. This test caught exactly that during development, when
 * staging and confirmation briefly lived in one class.</p>
 *
 * <p>Tools are discovered by scanning rather than listed, so a tool added later is
 * covered without anyone remembering to add it. The whole dependency graph is
 * walked rather than just the direct fields, because the dangerous version of
 * this mistake is the indirect one.</p>
 */
class ToolsCannotReachPaymentClientTest {

    private static final String AGENT_PACKAGE = "com.digitalbank.aicommerce";

    @Test
    @DisplayName("no AgentTool can reach PaymentFeignClient, directly or transitively")
    void toolsCannotReachThePaymentClient() {

        List<Class<?>> tools = discoverTools();

        assertThat(tools)
                .as("tool scanning found nothing, so this test would pass vacuously")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (Class<?> tool : tools) {
            List<String> route = routeTo(tool, PaymentFeignClient.class);
            if (route != null) {
                violations.add(String.join(" -> ", route));
            }
        }

        assertThat(violations)
                .as("a tool the model can call reaches the payment client; "
                        + "model output could then cause money to move")
                .isEmpty();
    }

    @Test
    @DisplayName("the chat path cannot reach PaymentFeignClient")
    void chatPathCannotReachThePaymentClient() {

        // Wider than the tool check: the orchestrator handles model output
        // directly, so it must not hold a route to the payment API either.
        assertThat(routeTo(AgentChatController.class, PaymentFeignClient.class))
                .as("the chat endpoint reaches the payment client")
                .isNull();

        assertThat(routeTo(AgentOrchestrator.class, PaymentFeignClient.class))
                .as("the orchestrator reaches the payment client")
                .isNull();
    }

    @Test
    @DisplayName("the confirmation path cannot reach the model")
    void confirmationPathCannotReachTheModel() {

        // The other direction: confirming must not involve the model at all.
        assertThat(routeTo(ProposalController.class, GeminiClient.class))
                .as("the confirm endpoint reaches the Gemini client")
                .isNull();

        assertThat(routeTo(ProposalController.class, AgentOrchestrator.class))
                .as("the confirm endpoint reaches the agent orchestrator")
                .isNull();
    }

    @Test
    @DisplayName("the confirmation path does reach the payment client")
    void confirmationPathReachesThePaymentClient() {

        // Proves the walk is looking at a real, wired path: if confirmation could
        // not reach the client either, the negative checks above would be
        // meaningless.
        assertThat(routeTo(ProposalController.class, PaymentFeignClient.class))
                .as("confirm endpoint should reach the payment client")
                .isNotNull();
    }

    @Test
    @DisplayName("the walk does detect a reachable payment client")
    void detectsAReachableClient() {

        // Guards the guard: if the traversal silently stopped working, the tests
        // above would pass no matter what the code did.
        assertThat(routeTo(ToolReachingPaymentClient.class, PaymentFeignClient.class))
                .as("a deliberately offending class must be reported")
                .isNotNull();
    }

    /** Reaches the payment client through one hop. Never registered. */
    @SuppressWarnings("unused")
    private static class OffendingCollaborator {
        private PaymentFeignClient paymentClient;
    }

    @SuppressWarnings("unused")
    private static class ToolReachingPaymentClient {
        private OffendingCollaborator collaborator;
    }

    private List<Class<?>> discoverTools() {

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentTool.class));

        List<Class<?>> tools = new ArrayList<>();

        for (BeanDefinition definition : scanner.findCandidateComponents(AGENT_PACKAGE)) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                if (!type.isInterface() && !type.getName().contains("$")) {
                    tools.add(type);
                }
            } catch (ClassNotFoundException ex) {
                throw new AssertionError("could not load scanned tool " + definition, ex);
            }
        }

        return tools;
    }

    /**
     * Breadth-first walk of declared field types, staying inside the agent's own
     * packages. Returns the route that reaches the target, or null.
     */
    private List<String> routeTo(Class<?> root, Class<?> target) {

        Set<Class<?>> seen = new HashSet<>();
        Deque<List<Class<?>>> queue = new ArrayDeque<>();

        queue.add(List.of(root));
        seen.add(root);

        while (!queue.isEmpty()) {

            List<Class<?>> path = queue.poll();
            Class<?> current = path.get(path.size() - 1);

            for (Field field : current.getDeclaredFields()) {

                Class<?> type = field.getType();

                if (target.isAssignableFrom(type)) {
                    List<Class<?>> found = new ArrayList<>(path);
                    found.add(type);
                    return found.stream().map(Class::getSimpleName).toList();
                }

                // Only the agent's own types are worth descending into; the JDK
                // and Spring cannot be holding these clients.
                if (type.getName().startsWith(AGENT_PACKAGE) && seen.add(type)) {
                    List<Class<?>> next = new ArrayList<>(path);
                    next.add(type);
                    queue.add(next);
                }
            }
        }

        return null;
    }
}
