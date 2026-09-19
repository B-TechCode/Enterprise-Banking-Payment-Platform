package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.account.dto.AccountRequest;
import com.account.dto.AccountResponse;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.exception.OwnerAccessDeniedException;
import com.commons.security.CurrentUser;

/**
 * Regression tests for the replay path of AccountService.create.
 *
 * <p>create treats a request whose fingerprint matches an existing account as a
 * replay and returns that account. The fingerprint is the caller's
 * Idempotency-Key when one is sent, otherwise a 32-bit hash of caller-supplied
 * fields (customer id, type, sub-type, currency, nickname, display name). Either
 * way, another customer can produce a match: deliberately, by reusing a key or
 * reproducing the hash from known values, or by accident, through a hash
 * collision.</p>
 *
 * <p>The replay path used to return the matched account before any ownership
 * check, handing one customer another customer's account, balance included. It
 * now applies the same check as every other account operation, so a replay
 * still works for whoever may see the account and is refused for anyone else.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceCreateReplayTest {

    private static final String OWNER = "cust-owner";
    private static final String OTHER = "cust-other";

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionService transactionService;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    private AccountService service;
    private Account ownersAccount;
    private AccountResponse ownersView;
    private AccountRequest request;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        UUID id = UUID.randomUUID();
        ownersAccount = Account.builder()
                .id(id)
                .customerId(OWNER)
                .balance(new BigDecimal("9999.00"))
                .build();
        ownersView = new AccountResponse(id, OWNER, "12345678", AccountType.CHEQUING,
                AccountSubType.PERSONAL, AccountStatus.ACTIVE, "USD", null, null,
                new BigDecimal("9999.00"), "****5678", 1);

        request = new AccountRequest(OWNER, AccountType.CHEQUING, AccountSubType.PERSONAL,
                AccountStatus.ACTIVE, "USD", null, null, null);

        // Every request in this class matches the owner's existing account.
        when(accountRepo.findByRequestFingerprint(anyString())).thenReturn(Optional.of(ownersAccount));
        when(mapper.toDto(ownersAccount)).thenReturn(ownersView);
    }

    private void callerIsCustomer(String customerId) {
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(customerId));
    }

    @Test
    @DisplayName("another customer whose request matches the fingerprint is refused, and gets nothing back")
    void matchOnSomeoneElsesAccountRefused() {
        callerIsCustomer(OTHER);

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(OwnerAccessDeniedException.class);

        verify(mapper, never()).toDto(any());
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("a reused Idempotency-Key belonging to another customer's create is refused")
    void reusedIdempotencyKeyRefused() {
        // With a key, the fingerprint is the key itself, so knowing or guessing
        // another customer's key was enough to read their account.
        callerIsCustomer(OTHER);

        assertThatThrownBy(() -> service.create(request, "owners-key"))
                .isInstanceOf(OwnerAccessDeniedException.class);

        verify(mapper, never()).toDto(any());
    }

    @Test
    @DisplayName("the owner replaying their own create still gets the original account back")
    void ownerReplayStillIdempotent() {
        // The fix must not break idempotency: a genuine retry returns the same
        // account and creates nothing new.
        callerIsCustomer(OWNER);

        assertThat(service.create(request, "owners-key")).isEqualTo(ownersView);
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("a client-credentials service replaying a create still gets the account back")
    void serviceReplayAllowed() {
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());

        assertThat(service.create(request, "service-key")).isEqualTo(ownersView);
        verify(accountRepo, never()).save(any());
    }
}
