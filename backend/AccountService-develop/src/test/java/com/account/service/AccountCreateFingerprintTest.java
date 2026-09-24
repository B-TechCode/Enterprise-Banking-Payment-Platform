package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountRequest;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.security.CurrentUser;

/**
 * The fingerprint that makes a keyless account create safe to retry.
 *
 * <p>It was a 32-bit String.hashCode, stored in a column unique across every
 * customer, so two unrelated requests could collide by accident and the second
 * would fail. It is now a SHA-256 over the same fields, and unique per customer.
 * These tests pin its shape and what it is computed from; the constraint itself
 * is proven against Postgres by FingerprintMigrationIT.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountCreateFingerprintTest {

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

        // Nothing has been created yet, so every create goes through to save.
        when(accountRepo.findByCustomerIdAndRequestFingerprint(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(mapper.toEntity(any(AccountRequest.class))).thenAnswer(call -> {
            AccountRequest r = call.getArgument(0);
            return Account.builder().customerId(r.customerId()).build();
        });
        when(accountRepo.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));

        // A trusted service caller, so any customer can be created for;
        // ownership is covered by AccountServiceOwnershipTest.
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    private static AccountRequest request(String customerId, String nickname) {
        return new AccountRequest(customerId, AccountType.CHEQUING, AccountSubType.PERSONAL,
                AccountStatus.ACTIVE, "CAD", nickname, "Everyday", null);
    }

    /** Creates an account and returns the fingerprint it was stored with. */
    private String fingerprintOf(AccountRequest request, String idempotencyKey) {
        org.mockito.Mockito.clearInvocations(accountRepo);
        service.create(request, idempotencyKey);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepo).save(saved.capture());
        return saved.getValue().getRequestFingerprint();
    }

    @Test
    @DisplayName("a keyless create is fingerprinted with SHA-256, not a 32-bit hash")
    void keylessFingerprintIsSha256() {
        assertThat(fingerprintOf(request("cust-1", "Main"), null))
                .as("64 hex characters; the old hashCode gave at most 8")
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the fingerprint is exactly the SHA-256 of the request's fields, customer id first")
    void fingerprintIsPinned() {
        // Pinned to the exact value because changing how it is computed is not a
        // refactor: every stored fingerprint silently stops matching its retry,
        // the one-deploy window V1__scope_idempotency_fingerprints describes.
        // Changing the formula should fail here and be a decision.
        assertThat(fingerprintOf(request("cust-1", "Main"), null))
                .isEqualTo(DigestUtils.sha256Hex("CUST-1|CHEQUING|PERSONAL|CAD|MAIN|EVERYDAY"));
    }

    @Test
    @DisplayName("an identical request is fingerprinted identically, so its retry is recognised")
    void fingerprintIsStable() {
        assertThat(fingerprintOf(request("cust-1", "Main"), null))
                .isEqualTo(fingerprintOf(request("cust-1", "Main"), null));
    }

    @Test
    @DisplayName("two customers making identical requests get different fingerprints")
    void customerIdIsPartOfTheFingerprint() {
        assertThat(fingerprintOf(request("cust-1", "Main"), null))
                .isNotEqualTo(fingerprintOf(request("cust-2", "Main"), null));
    }

    @Test
    @DisplayName("a different request from the same customer gets a different fingerprint")
    void differentRequestDifferentFingerprint() {
        assertThat(fingerprintOf(request("cust-1", "Main"), null))
                .isNotEqualTo(fingerprintOf(request("cust-1", "Savings"), null));
    }

    @Test
    @DisplayName("with an Idempotency-Key, the key itself is stored, trimmed")
    void idempotencyKeyIsStoredAsGiven() {
        assertThat(fingerprintOf(request("cust-1", "Main"), "  client-key-7  "))
                .isEqualTo("client-key-7");
    }

    @Test
    @DisplayName("the replay lookup is made under the customer the request names")
    void lookupIsScopedToTheRequestsCustomer() {
        service.create(request("cust-9", "Main"), "client-key-7");

        verify(accountRepo).findByCustomerIdAndRequestFingerprint("cust-9", "client-key-7");
    }
}
