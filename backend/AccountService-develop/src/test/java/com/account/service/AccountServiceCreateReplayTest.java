package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * Idempotency-Key when one is sent, otherwise a hash of the request's fields.</p>
 *
 * <p>The replay path used to look fingerprints up across every customer and
 * return the match before any ownership check, so another customer reusing a key
 * or reproducing a hash was handed someone else's account, balance included.
 * Three layers now stand in the way, and each is tested here on its own:</p>
 *
 * <ol>
 *   <li>the caller must be allowed to create for the customer named in the
 *       request before anything is looked up;</li>
 *   <li>the lookup is scoped to that customer, so another customer's key finds
 *       nothing and simply creates the caller's own account;</li>
 *   <li>a replayed account is ownership-checked again, in case the lookup's
 *       scoping is ever lost.</li>
 * </ol>
 *
 * <p>The repository mock answers as the scoped query does: the owner's account
 * is found only under the owner's customer id.</p>
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
    private AccountRequest ownersRequest;

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

        ownersRequest = requestFor(OWNER);

        // As the database answers: the owner's account exists under every
        // fingerprint, but only when the lookup names the owner.
        when(accountRepo.findByCustomerIdAndRequestFingerprint(eq(OWNER), anyString()))
                .thenReturn(Optional.of(ownersAccount));
        when(mapper.toDto(ownersAccount)).thenReturn(ownersView);

        // A create that gets past the replay path builds and saves a new account.
        when(mapper.toEntity(any(AccountRequest.class))).thenAnswer(call -> {
            AccountRequest r = call.getArgument(0);
            return Account.builder().customerId(r.customerId()).build();
        });
        when(accountRepo.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static AccountRequest requestFor(String customerId) {
        return new AccountRequest(customerId, AccountType.CHEQUING, AccountSubType.PERSONAL,
                AccountStatus.ACTIVE, "USD", null, null, null);
    }

    private void callerIsCustomer(String customerId) {
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(customerId));
    }

    @Test
    @DisplayName("a request naming another customer is refused before anything is looked up for them")
    void requestForSomeoneElseRefusedBeforeLookup() {
        callerIsCustomer(OTHER);

        assertThatThrownBy(() -> service.create(ownersRequest, "owners-key"))
                .isInstanceOf(OwnerAccessDeniedException.class);

        // Nothing is looked up on the owner's behalf, so a refusal cannot
        // double as an answer to "does this customer have an account under
        // this key?".
        verify(accountRepo, never()).findByCustomerIdAndRequestFingerprint(any(), any());
        verify(mapper, never()).toDto(any());
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("another customer reusing a key gets an account of their own, never the owner's")
    void reusedKeyCreatesTheCallersOwnAccount() {
        // Before the lookup was scoped, this was a refusal at best and, before
        // that, a leak. Scoped per customer, one customer's key means nothing to
        // another: the second customer is not blocked from using it, and cannot
        // reach the first customer's account through it.
        callerIsCustomer(OTHER);

        service.create(requestFor(OTHER), "owners-key");

        verify(accountRepo).findByCustomerIdAndRequestFingerprint(OTHER, "owners-key");
        verify(mapper, never()).toDto(ownersAccount);
        verify(accountRepo).save(org.mockito.ArgumentMatchers.argThat(
                saved -> OTHER.equals(saved.getCustomerId()) && "owners-key".equals(saved.getRequestFingerprint())));
    }

    @Test
    @DisplayName("a replayed account is still ownership-checked, should the lookup ever return another's")
    void replayedAccountCheckedEvenIfLookupScopingFails() {
        // Simulates the lookup's scoping being lost: the query for OTHER hands
        // back the owner's account. The second check must still refuse it.
        callerIsCustomer(OTHER);
        when(accountRepo.findByCustomerIdAndRequestFingerprint(eq(OTHER), anyString()))
                .thenReturn(Optional.of(ownersAccount));

        assertThatThrownBy(() -> service.create(requestFor(OTHER), "owners-key"))
                .isInstanceOf(OwnerAccessDeniedException.class);

        verify(mapper, never()).toDto(any());
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("the owner replaying their own create still gets the original account back")
    void ownerReplayStillIdempotent() {
        // The fix must not break idempotency: a genuine retry returns the same
        // account and creates nothing new.
        callerIsCustomer(OWNER);

        assertThat(service.create(ownersRequest, "owners-key")).isEqualTo(ownersView);
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("the owner replaying a keyless create still gets the original account back")
    void ownerKeylessReplayStillIdempotent() {
        callerIsCustomer(OWNER);

        assertThat(service.create(ownersRequest, null)).isEqualTo(ownersView);
        verify(accountRepo, never()).save(any());
    }

    @Test
    @DisplayName("a client-credentials service replaying a create still gets the account back")
    void serviceReplayAllowed() {
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());

        assertThat(service.create(ownersRequest, "service-key")).isEqualTo(ownersView);
        verify(accountRepo, never()).save(any());
    }
}
