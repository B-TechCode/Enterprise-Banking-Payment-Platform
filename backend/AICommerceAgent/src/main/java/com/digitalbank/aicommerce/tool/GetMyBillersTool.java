package com.digitalbank.aicommerce.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.digitalbank.aicommerce.client.dto.BillerView;
import com.digitalbank.aicommerce.service.BillerQueryService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only. Returns the billers the caller has registered.
 *
 * <p>Exists so the model can turn a name a user said, such as "the electricity
 * bill", into the exact reference number a payment needs. Without it the model
 * would have to guess a reference number, which is the kind of guess that must
 * never reach a payment.</p>
 */
@Component
@RequiredArgsConstructor
public class GetMyBillersTool implements AgentTool {

    private final BillerQueryService billerQueryService;

    @Override
    public String name() {
        return "get_my_billers";
    }

    @Override
    public String description() {
        return "Returns the billers the user has registered, each with its name, "
                + "category, status and referenceNumber. Takes no arguments. Call this "
                + "to find the exact referenceNumber for a biller the user named before "
                + "proposing a payment. Never invent a referenceNumber.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, String conversationId) {

        List<BillerView> billers = billerQueryService.myBillers(conversationId);

        List<Map<String, Object>> view = billers.stream()
                .map(biller -> Map.<String, Object>of(
                        "name", nullSafe(biller.name()),
                        "referenceNumber", nullSafe(biller.referenceNumber()),
                        "category", nullSafe(biller.category()),
                        "status", nullSafe(biller.status())))
                .toList();

        return Map.of("billers", view);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
