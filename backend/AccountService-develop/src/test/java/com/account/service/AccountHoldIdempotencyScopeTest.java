package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountStatus;
import com.account.dto.CreateHoldRequest;
import com.account.dto.HoldStatus;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.AccountHold;
import com.account.model.Transaction;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.security.CurrentUser;

/**
 * An idempotency key belongs to the account it was used on.
 *
 * <p>Placing a hold treats a known key as a replay and returns the hold it
 * finds. That lookup used to search every account, so a caller who reused a key
 * another customer had used was handed that customer's hold: its id and the
 * amount reserved. The lookup is now scoped to the account, which is also how
 * the postings idempotency check works.</p>
 *
 * <p>The unique constraint is per account too (uk_hold_account_fingerprint,
 * created on existing databases by V1__scope_idempotency_fingerprints), so a
 * key another account already used places this account's hold normally, where
 * under the old global constraint it failed the write.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountHoldIdempotencyScopeTest {

    private static final String CUSTOMER = "cust-1";
    private static final UUID MY_ACCOUNT = UUID.randomUUID();
    private static final UUID ANOTHER_ACCOUNT = UUID.randomUUID();
    private static final String KEY = "shared-key-001";

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionService transactionService;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        Account mine = Account.builder()
                .id(MY_ACCOUNT)
                .customerId(CUSTOMER)
                .currency("CAD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();

        when(accountRepo.findById(MY_ACCOUNT)).thenReturn(Optional.of(mine));
        when(holdRepo.findByAccountIdAndStatus(any(), any())).thenReturn(List.of());
        when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(transactionMapper.toEntity(any())).thenAnswer(call -> new Transaction());

        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(CUSTOMER));

        // Another customer's hold under the same key, found only by a lookup
        // that names that account, as the database would answer.
        when(holdRepo.findByAccountIdAndRequestFingerprint(ANOTHER_ACCOUNT, KEY)).thenReturn(Optional.of(AccountHold.builder()
                .id(UUID.randomUUID())
                .accountId(ANOTHER_ACCOUNT)
                .amount(new BigDecimal("9999.00"))
                .status(HoldStatus.ACTIVE)
                .build()));

        // Nothing on this account carries that key.
        when(holdRepo.findByAccountIdAndRequestFingerprint(eq(MY_ACCOUNT), anyString()))
                .thenReturn(Optional.empty());
    }

    private CreateHoldRequest request() {
        return new CreateHoldRequest(new BigDecimal("25.00"), "CAD", "billpay", (LocalDateTime) null, KEY);
    }

    @Test
    @DisplayName("a key used on another account does not return that account's hold")
    void keyFromAnotherAccountIsNotAReplay() {
        var response = service.createHold(MY_ACCOUNT, request());

        assertThat(response.amount())
                .as("the reply must describe this caller's hold, not the other account's")
                .isEqualByComparingTo("25.00");
        verify(holdRepo).save(any());
    }

    @Test
    @DisplayName("the replay lookup is scoped to the account")
    void lookupIsScopedToTheAccount() {
        service.createHold(MY_ACCOUNT, request());

        verify(holdRepo).findByAccountIdAndRequestFingerprint(MY_ACCOUNT, KEY);
        // No unscoped lookup is left on the repository to call: it was removed,
        // so reintroducing one is a compile error rather than something this
        // test has to catch.
    }

    @Test
    @DisplayName("the caller's own key is still a replay and places no second hold")
    void ownKeyIsStillAReplay() {
        UUID existingHold = UUID.randomUUID();
        when(holdRepo.findByAccountIdAndRequestFingerprint(MY_ACCOUNT, KEY))
                .thenReturn(Optional.of(AccountHold.builder()
                        .id(existingHold)
                        .accountId(MY_ACCOUNT)
                        .amount(new BigDecimal("25.00"))
                        .status(HoldStatus.ACTIVE)
                        .build()));

        var response = service.createHold(MY_ACCOUNT, request());

        assertThat(response.holdId()).isEqualTo(existingHold);
        verify(holdRepo, never()).save(any());
    }
}
