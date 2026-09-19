package com.digitalbank.aicommerce.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.commons.exception.ForbiddenException;
import com.digitalbank.aicommerce.config.AgentProperties;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.dto.ChatRequest;
import com.digitalbank.aicommerce.dto.ChatResponse;
import com.digitalbank.aicommerce.dto.ProposalSummary;
import com.digitalbank.aicommerce.llm.GeminiClient;
import com.digitalbank.aicommerce.llm.GeminiContent;
import com.digitalbank.aicommerce.llm.GeminiException;
import com.digitalbank.aicommerce.llm.GeminiFunctionCall;
import com.digitalbank.aicommerce.llm.GeminiPart;
import com.digitalbank.aicommerce.llm.GeminiResponse;
import com.digitalbank.aicommerce.tool.AgentTool;
import com.digitalbank.aicommerce.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives the Gemini tool-use loop by hand.
 *
 * <p>A manual loop is used rather than an SDK tool runner because the turn must
 * stop at a proposal and hand control back to a human across an HTTP boundary.
 * The loop also gives one interception point per tool call for the audit trail
 * and for rejecting any tool name that is not registered.</p>
 *
 * <p>The loop is the only thing standing between model output and a banking API,
 * so it treats every field the model produced as untrusted: the tool name is
 * matched against the registry rather than dispatched, and the identity the tool
 * acts for is taken from the caller's token, never from the arguments.</p>
 *
 * <p>State: a turn is self-contained. History is not carried between HTTP
 * requests in this slice, so each request starts a fresh exchange and a
 * follow-up question that depends on an earlier message will not resolve. The
 * conversation id groups the audit trail; it does not yet restore context.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {

    private static final String TURN_TOOL_NAME = "agent_chat_turn";

    /** Shown to the user when the model produced nothing usable. */
    private static final String NO_REPLY =
            "Sorry, I wasn't able to answer that. Please try asking again.";

    private final GeminiClient geminiClient;
    private final ToolRegistry toolRegistry;
    private final AuditService auditService;
    private final CallerIdentity callerIdentity;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Used only to read a staged proposal back for the reply. The orchestrator
     * never calls {@code confirm}: execution is not reachable from a chat turn.
     */
    private final ProposalService proposalService;

    /**
     * Runs one conversation turn to completion: model, tools, model again, until
     * the model answers in words or the iteration budget runs out.
     */
    public ChatResponse chat(ChatRequest request) {

        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId()
                : UUID.randomUUID().toString();

        // Resolved before anything else: an unauthorized caller must not reach
        // the model at all, and the refusal is audited like any other action.
        String customerId;
        try {
            customerId = callerIdentity.requireCustomerId();
        } catch (ForbiddenException denied) {
            auditTurn(conversationId, null, ActionOutcome.DENIED, denied.getMessage());
            throw denied;
        }

        String subject = callerIdentity.subject();

        try {
            TurnOutcome turn = runLoop(request.message(), conversationId);

            // Read back from storage rather than from the tool result, so what the
            // user is shown is the stored proposal and not a value that travelled
            // through anything the model produced.
            ProposalSummary proposal = turn.proposalId() == null
                    ? null
                    : proposalService.findSummary(turn.proposalId(), customerId).orElse(null);

            auditTurn(conversationId, customerId, subject, ActionOutcome.SUCCESS,
                    "turn completed, reply length " + turn.reply().length()
                            + (proposal == null ? "" : ", proposal " + proposal.proposalId()));

            return new ChatResponse(conversationId, turn.reply(), proposal);

        } catch (ForbiddenException denied) {
            auditTurn(conversationId, customerId, subject, ActionOutcome.DENIED, denied.getMessage());
            throw denied;

        } catch (RuntimeException failure) {
            auditTurn(conversationId, customerId, subject, ActionOutcome.ERROR, failure.getMessage());
            throw failure;
        }
    }

    /** What one turn produced: words for the user, and a proposal if one was staged. */
    private record TurnOutcome(String reply, String proposalId) {
    }

    /** One tool call's result: the part to send back, and any proposal it staged. */
    private record DispatchResult(GeminiPart part, String proposalId) {
    }

    private TurnOutcome runLoop(String userMessage, String conversationId) {

        GeminiContent systemInstruction = GeminiContent.user(AgentSystemPrompt.TEXT);

        List<GeminiContent> conversation = new ArrayList<>();
        conversation.add(GeminiContent.user(userMessage));

        String stagedProposalId = null;

        for (int iteration = 0; iteration < properties.getMaxToolIterations(); iteration++) {

            GeminiResponse response = geminiClient.generate(
                    systemInstruction, conversation, toolRegistry.declarations());

            GeminiContent modelTurn = response.firstContent();

            if (modelTurn == null || modelTurn.parts() == null || modelTurn.parts().isEmpty()) {
                log.warn("empty model turn conversation={} feedback={}",
                        conversationId, response.promptFeedback());
                return new TurnOutcome(NO_REPLY, stagedProposalId);
            }

            // Appended verbatim, which is what preserves each part's thought
            // signature. Rebuilding this message would strip those and the model
            // would lose the reasoning behind its own tool call.
            conversation.add(modelTurn);

            List<GeminiPart> toolCalls = modelTurn.parts().stream()
                    .filter(GeminiPart::hasFunctionCall)
                    .toList();

            if (toolCalls.isEmpty()) {
                return new TurnOutcome(extractText(modelTurn, conversationId), stagedProposalId);
            }

            List<GeminiPart> results = new ArrayList<>();
            for (GeminiPart call : toolCalls) {
                DispatchResult dispatched = dispatch(call.functionCall(), conversationId);
                results.add(dispatched.part());

                if (dispatched.proposalId() != null) {
                    stagedProposalId = dispatched.proposalId();
                }
            }

            conversation.add(GeminiContent.toolResults(results));
        }

        // The budget exists so a model that keeps calling the same tool cannot
        // hold the request open indefinitely.
        log.warn("tool iteration budget exhausted conversation={}", conversationId);
        throw new GeminiException(
                "The assistant could not finish that request. Please try again.");
    }

    /**
     * Runs one tool call, or refuses it.
     *
     * <p>A failure is returned to the model as a result rather than thrown, so it
     * can tell the user something useful instead of the request collapsing. The
     * exception is an authorization failure, which ends the turn.</p>
     */
    private DispatchResult dispatch(GeminiFunctionCall call, String conversationId) {

        String name = call == null ? null : call.name();
        Map<String, Object> arguments = call == null || call.args() == null ? Map.of() : call.args();
        String callId = call == null ? null : call.id();

        AgentTool tool = toolRegistry.find(name).orElse(null);

        if (tool == null) {
            // The model named something outside the allowlist. Refused and
            // recorded: this is the entry worth having in the log.
            log.warn("refused unregistered tool '{}' conversation={}", name, conversationId);

            auditService.record(
                    withInput(AgentActionLog.starting(
                            String.valueOf(name), null, callerIdentity.subject(), conversationId),
                            arguments),
                    ActionOutcome.DENIED,
                    "tool is not in the agent allowlist",
                    null);

            return new DispatchResult(
                    GeminiPart.ofFunctionResponse(callId, String.valueOf(name),
                            Map.of("error", "This tool is not available to you.")),
                    null);
        }

        try {
            // The tool audits its own downstream call; the identity it acts for
            // comes from the token inside, not from anything passed here.
            Map<String, Object> result = tool.execute(arguments, conversationId);

            // A staged proposal is noted so the reply can carry it. Only the id
            // travels; the details are read back from storage afterwards.
            Object proposalId = result.get("proposalId");

            return new DispatchResult(
                    GeminiPart.ofFunctionResponse(callId, tool.name(), result),
                    proposalId == null ? null : String.valueOf(proposalId));

        } catch (ForbiddenException denied) {
            throw denied;

        } catch (RuntimeException failure) {
            log.error("tool '{}' failed conversation={}", tool.name(), conversationId, failure);

            return new DispatchResult(
                    GeminiPart.ofFunctionResponse(callId, tool.name(),
                            Map.of("error", "This information could not be retrieved right now.")),
                    null);
        }
    }

    private String extractText(GeminiContent modelTurn, String conversationId) {

        String text = modelTurn.parts().stream()
                .map(GeminiPart::text)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining("\n"))
                .trim();

        if (text.isEmpty()) {
            log.warn("model turn carried no text conversation={}", conversationId);
            return NO_REPLY;
        }

        return text;
    }

    private AgentActionLog withInput(AgentActionLog entry, Map<String, Object> arguments) {
        try {
            entry.setInputJson(objectMapper.writeValueAsString(arguments));
        } catch (JsonProcessingException ex) {
            entry.setInputJson("<unserializable arguments>");
        }
        return entry;
    }

    private void auditTurn(String conversationId, String customerId,
                           ActionOutcome outcome, String summary) {
        auditTurn(conversationId, customerId, null, outcome, summary);
    }

    /** Every turn leaves a record, whether it succeeded, was refused or failed. */
    private void auditTurn(String conversationId, String customerId, String subject,
                           ActionOutcome outcome, String summary) {
        auditService.record(
                AgentActionLog.starting(TURN_TOOL_NAME, customerId, subject, conversationId),
                outcome,
                summary,
                null);
    }
}
