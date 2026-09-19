package com.digitalbank.aicommerce.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A payment the agent has proposed and a human has not yet confirmed.
 *
 * <p>Persisted so it survives between the proposing request and the confirming
 * request. Execution reads its parameters from this record rather than from
 * anything said at confirmation time: once staged, the amount, the biller and
 * the account are fixed, and no later message can alter them.</p>
 *
 * <p>The id doubles as the Idempotency-Key sent to the payment orchestrator, so
 * a confirmation that is retried settles onto the same payment instead of
 * creating a second one.</p>
 */
@Entity
@Table(
        name = "payment_proposal",
        indexes = {
                @Index(name = "idx_proposal_customer", columnList = "customerId"),
                @Index(name = "idx_proposal_status", columnList = "status"),
                @Index(name = "idx_proposal_conversation", columnList = "conversationId")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProposal {

    /** Also the Idempotency-Key for the downstream payment. */
    @Id
    private UUID id;

    @Column(length = 64)
    private String conversationId;

    /** Owner of the proposal, taken from the token when it was staged. */
    @Column(nullable = false, length = 64)
    private String customerId;

    @Column(length = 128)
    private String subject;

    @Column(nullable = false)
    private UUID debtorAccountId;

    /** For display at confirmation time. The full number is never stored here. */
    @Column(length = 35)
    private String debtorAccountMasked;

    @Column(nullable = false, length = 64)
    private String billerReferenceNumber;

    /** Snapshot of the biller name as it read when the proposal was staged. */
    @Column(length = 140)
    private String billerName;

    @Column(nullable = false, length = 64)
    private String invoiceReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProposalStatus status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime confirmedAt;

    /** Set only once the orchestrator has accepted the payment. */
    private UUID paymentId;

    /**
     * Guards the confirmation transition. Two confirmations racing for the same
     * proposal cannot both move it out of PENDING_CONFIRMATION.
     */
    @Version
    private Integer version;

    /** Whether the confirmation window has closed. */
    public boolean isExpiredAt(OffsetDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
