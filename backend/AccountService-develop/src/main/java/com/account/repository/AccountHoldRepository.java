package com.account.repository;

import com.account.dto.HoldStatus;
import com.account.model.AccountHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;



public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {
    List<AccountHold> findByAccountIdAndStatus(UUID accountId, HoldStatus status);
    /**
     * A hold on this account recorded under this key.
     *
     * <p>Scoped to the account, so one customer's idempotency key cannot match
     * another customer's hold and return its id and amount. There is
     * deliberately no lookup by key alone: keys are unique per account.</p>
     */
    Optional<AccountHold> findByAccountIdAndRequestFingerprint(UUID accountId, String fingerprint);
    
    List<AccountHold> findByStatusAndReleaseAtLessThanEqual(HoldStatus status, LocalDateTime cutoff);

}