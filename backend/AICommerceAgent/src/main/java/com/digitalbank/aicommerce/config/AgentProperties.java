package com.digitalbank.aicommerce.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Tunables for the agent: model id, conversation limits, the largest amount that
 * may be proposed, and how long a proposal stays confirmable.
 *
 * <p>The API key is deliberately absent. It is read from the environment at
 * startup and never bound here, so it cannot reach a configuration file, a
 * config-server response or an actuator endpoint.</p>
 */
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class AgentProperties {

    /** Gemini model id, for example {@code gemini-3.5-flash}. */
    private String model = "gemini-3.5-flash";

    /** Base URL of the Gemini generative language API. */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /**
     * How many times the loop may hand a tool result back to the model within a
     * single turn. A bound is required: without one a model that keeps calling
     * the same tool would loop until the request times out.
     */
    private int maxToolIterations = 5;

    /** Timeout for a single call to the model. */
    private int requestTimeoutSeconds = 30;

    /** Sampling temperature. Low, because this is an account assistant. */
    private double temperature = 0.2;

    /**
     * Largest amount the agent may stage for confirmation. Above this it refuses
     * rather than proposing. Unused until the payment slice.
     */
    private BigDecimal maxProposalAmount = new BigDecimal("1000.00");

    /** How long a staged proposal stays confirmable. Unused until the payment slice. */
    private int proposalTtlSeconds = 300;

    /**
     * The only currency the platform settles bill payments in.
     *
     * <p>It mirrors the rule in the Payment Orchestrator's BillPayValidator,
     * which refuses anything else with CURRENCY_NOT_ALLOWED. Holding it here as
     * well lets the agent refuse a payment it knows will be refused, at the
     * point where it can still explain why, rather than staging a proposal that
     * fails at confirmation. Nothing below the payment record carries a
     * currency at all - holds and postings are bare amounts - so this is one
     * setting on each side of a boundary, not a feature flag: changing it here
     * alone only moves where the refusal happens.</p>
     */
    private String settlementCurrency = "CAD";
}
