package com.account.repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.account.model.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByCustomerId(String customerId);

    /**
     * This customer's account created under this fingerprint.
     *
     * <p>Scoped to the customer, as the unique constraint is. There is
     * deliberately no lookup by fingerprint alone: fingerprints are unique per
     * customer, so one would return another customer's account.</p>
     */
    Optional<Account> findByCustomerIdAndRequestFingerprint(String customerId, String fingerprint);

    Optional<Account> findByAccountNumber(String accountNumber);
}
