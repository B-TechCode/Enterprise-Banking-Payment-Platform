package com.account.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.account.dto.HoldStatus;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "account_hold",
       indexes = {
         @Index(name = "idx_hold_account", columnList = "accountId"),
         @Index(name = "idx_hold_status", columnList = "status")
       },
       uniqueConstraints = {
         // An Idempotency-Key is unique within an account, not across the
         // table. Created on existing databases by V1__scope_idempotency_fingerprints.
         @UniqueConstraint(name = "uk_hold_account_fingerprint",
                           columnNames = {"accountId", "requestFingerprint"})
       })
public class AccountHold {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    private String reason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Optional: auto-expire holds */
    private LocalDateTime releaseAt;

    /** Optional idempotency for holds; unique per account (see the table's constraints). */
    @Column(length = 128)
    private String requestFingerprint;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        createdAt = now; updatedAt = now;
        if (status == null) status = HoldStatus.ACTIVE;
        if (releaseAt == null) releaseAt = now.plusDays(7);
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.of("UTC"));
    }
}