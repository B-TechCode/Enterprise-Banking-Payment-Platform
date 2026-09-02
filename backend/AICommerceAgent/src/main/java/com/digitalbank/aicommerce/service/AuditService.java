package com.digitalbank.aicommerce.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;
import com.digitalbank.aicommerce.repo.AgentActionLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Records what the agent did, for whom, and how it ended.
 *
 * <p>Each entry is written in its own transaction so an audit record survives
 * even when the surrounding operation is rolled back. An action that failed or
 * was refused must still leave a trace.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AgentActionLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentActionLog record(AgentActionLog entry,
                                 ActionOutcome outcome,
                                 String resultSummary,
                                 Integer downstreamStatus) {

        entry.setOutcome(outcome);
        entry.setResultSummary(truncate(resultSummary));
        entry.setDownstreamStatus(downstreamStatus);

        AgentActionLog saved = repository.save(entry);

        log.info("agent action tool={} customer={} outcome={} status={}",
                saved.getToolName(), saved.getCustomerId(), outcome, downstreamStatus);

        return saved;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }
}
