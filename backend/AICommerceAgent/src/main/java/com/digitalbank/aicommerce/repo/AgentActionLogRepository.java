package com.digitalbank.aicommerce.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digitalbank.aicommerce.domain.AgentActionLog;

/**
 * Persistence for the AI audit trail.
 */
public interface AgentActionLogRepository extends JpaRepository<AgentActionLog, Long> {

    List<AgentActionLog> findByCustomerIdOrderByOccurredAtDesc(String customerId);
}
