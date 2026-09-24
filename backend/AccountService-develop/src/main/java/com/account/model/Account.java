package com.account.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.account.dto.*;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "account",
       indexes = {
         @Index(name = "idx_account_customer", columnList = "customerId"),
         @Index(name = "idx_account_status", columnList = "status")
       },
       uniqueConstraints = {
         // A create fingerprint is unique per customer, not across the table.
         // Replaces idx_account_fingerprint and the column's own unique
         // constraint, both global; V1__scope_idempotency_fingerprints drops
         // them from existing databases.
         @UniqueConstraint(name = "uk_account_customer_fingerprint",
                           columnNames = {"customerId", "requestFingerprint"})
       })
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String customerId;

    @Column(length = 20, nullable = false, unique = true)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountSubType accountSubType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(length = 3, nullable = false)
    private String currency;

    @Column(length = 64)
    private String nickname;

    @Column(length = 64)
    private String displayName;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal balance;

    /** Optimistic locking */
    @Version
    private Integer version;

    /** For idempotent create; unique per customer (see the table's constraints). */
    @Column(length = 128)
    private String requestFingerprint;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        createdAt = now; updatedAt = now;
        if (balance == null) balance = BigDecimal.ZERO;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.of("UTC"));
    }
}