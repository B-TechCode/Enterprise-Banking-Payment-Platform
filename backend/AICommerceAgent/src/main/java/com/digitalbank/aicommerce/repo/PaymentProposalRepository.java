package com.digitalbank.aicommerce.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digitalbank.aicommerce.domain.PaymentProposal;
import com.digitalbank.aicommerce.domain.ProposalStatus;

/**
 * Persistence for staged payment proposals.
 */
public interface PaymentProposalRepository extends JpaRepository<PaymentProposal, UUID> {

    List<PaymentProposal> findByCustomerIdAndStatusOrderByCreatedAtDesc(
            String customerId, ProposalStatus status);
}
