package com.digitalbank.aicommerce.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per action the agent takes on behalf of a user.
 *
 * <p>Written for every attempt, including denials and failures. A refused
 * attempt is the most interesting entry in the log, so nothing is recorded only
 * on the success path.</p>
 */
@Entity
@Table(
        name = "agent_action_log",
        indexes = {
                @Index(name = "idx_agent_log_customer", columnList = "customerId"),
                @Index(name = "idx_agent_log_occurred", columnList = "occurredAt"),
                @Index(name = "idx_agent_log_conversation", columnList = "conversationId")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Groups actions belonging to one conversation. */
    @Column(length = 64)
    private String conversationId;

    /** Customer identity taken from the token, never from user or model input. */
    @Column(length = 64)
    private String customerId;

    /** Auth0 subject, so a service call can be told apart from a user call. */
    @Column(length = 128)
    private String subject;

    /** Tool or capability invoked. */
    @Column(nullable = false, length = 80)
    private String toolName;

    /** Input the tool was called with, as JSON. */
    @Lob
    private String inputJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActionOutcome outcome;

    /** Short human-readable result, never the full downstream payload. */
    @Column(length = 512)
    private String resultSummary;

    /** HTTP status returned by the downstream service, when there was one. */
    private Integer downstreamStatus;

    /** Correlation id, to line this up with the platform access logs. */
    @Column(length = 64)
    private String correlationId;

    @Column(nullable = false)
    private OffsetDateTime occurredAt;

    public static AgentActionLog starting(String toolName, String customerId, String subject) {
        return AgentActionLog.builder()
                .conversationId(UUID.randomUUID().toString())
                .customerId(customerId)
                .subject(subject)
                .toolName(toolName)
                .occurredAt(OffsetDateTime.now())
                .build();
    }
}
