package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.dto.HoldStatus;
import com.account.dto.PostingRequest;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.AccountHold;
import com.account.model.Transaction;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.security.CurrentUser;

/**
 * Postings must be safe to repeat.
 *
 * <p>The Payment Orchestrator debits an account when settlement confirms a
 * payment. That confirmation arrives at least once, and the debit is an HTTP
 * call that cannot be rolled back by the orchestrator's own transaction: if
 * anything fails locally after it, the event is redelivered and the debit is
 * made again. A caller that may retry therefore sends a key that is the same
 * for every attempt, and the posting is applied once.</p>
 *
 * <p>The check has to guard the balance rather than the ledger.
 * TransactionService.save already returns the existing transaction for a
 * repeated fingerprint, so guarding only the ledger would move the money twice
 * and record it once, leaving the ledger unable to explain the balance. The
 * tests below assert the balance and the ledger together for that reason.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountPostingIdempotencyTest {

    private static final String CUSTOMER = "cust-1";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID HOLD_ID = UUID.randomUUID();
    private static final String KEY = "payment-7f3a:debit";

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionService transactionService;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    private AccountService service;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        account = Account.builder()
                .id(ACCOUNT_ID)
                .customerId(CUSTOMER)
                .currency("CAD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();

        when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(mapper.toDto(any())).thenAnswer(call -> {
            Account a = call.getArgument(0);
            return new com.account.dto.AccountResponse(a.getId(), a.getCustomerId(), "12345678",
                    AccountType.CHEQUING, AccountSubType.PERSONAL, a.getStatus(), a.getCurrency(),
                    null, null, a.getBalance(), "****5678", a.getVersion());
        });
        when(transactionMapper.toEntity(any())).thenAnswer(call -> new Transaction());
        when(holdRepo.findByAccountIdAndStatus(any(), any())).thenReturn(List.of());

        // The caller is the account's owner; ownership is covered elsewhere.
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(CUSTOMER));

        // Nothing has been posted under any key until a test says otherwise.
        when(transactionService.findByAccountAndFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());
    }

    private PostingRequest posting() {
        return new PostingRequest(new BigDecimal("75.00"), "billpay");
    }

    /** Makes the next call look like a repeat of a posting already applied. */
    private void alreadyPosted(String key) {
        when(transactionService.findByAccountAndFingerprint(ACCOUNT_ID, key))
                .thenReturn(Optional.of(new Transaction()));
    }

    private String fingerprintOfSavedTransaction() {
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionService).save(saved.capture());
        return saved.getValue().getRequestFingerprint();
    }

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        @DisplayName("a repeat under the same key takes no money and records nothing")
        void repeatUnderSameKeyTakesNoMoney() {
            alreadyPosted(KEY);

            var response = service.debit(ACCOUNT_ID, posting(), null, KEY);

            assertThat(account.getBalance())
                    .as("the balance must not move a second time")
                    .isEqualByComparingTo("500.00");
            assertThat(response.balance()).isEqualByComparingTo("500.00");
            verify(accountRepo, never()).saveAndFlush(any());
            verify(transactionService, never()).save(any());
        }

        @Test
        @DisplayName("the first posting under a key debits once and records that key")
        void firstPostingDebitsAndRecordsKey() {
            service.debit(ACCOUNT_ID, posting(), null, KEY);

            assertThat(account.getBalance()).isEqualByComparingTo("425.00");
            assertThat(fingerprintOfSavedTransaction())
                    .as("the key becomes the transaction's fingerprint, so a repeat is recognised")
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("a different key is a different posting and debits again")
        void differentKeyDebitsAgain() {
            alreadyPosted("some-other-payment:debit");

            service.debit(ACCOUNT_ID, posting(), null, KEY);

            assertThat(account.getBalance()).isEqualByComparingTo("425.00");
        }

        @Test
        @DisplayName("without a key the posting is applied as before")
        void withoutKeyBehavesAsBefore() {
            service.debit(ACCOUNT_ID, posting(), null, null);

            assertThat(account.getBalance()).isEqualByComparingTo("425.00");
            assertThat(fingerprintOfSavedTransaction())
                    .as("falls back to the generated fingerprint, which is unique per call")
                    .isNotEqualTo(KEY);
        }

        @Test
        @DisplayName("a losing race on the unique constraint fails, so the debit rolls back with it")
        void constraintViolationPropagates() {
            // Two retries can both pass the lookup. The unique constraint on
            // (accountId, requestFingerprint) then rejects the second write,
            // and because the ledger row and the balance are written in one
            // transaction, the second debit is undone rather than kept.
            when(transactionService.save(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_tx_account_idem"));

            assertThatThrownBy(() -> service.debit(ACCOUNT_ID, posting(), null, KEY))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("credit")
    class Credit {

        @BeforeEach
        void callerIsAnAdministrator() {
            // Only an administrator may credit at all, since a credit brings
            // money in from nowhere; AccountFundingAuthorizationTest covers who.
            // What is being tested here is that a repeat adds money once.
            when(currentUser.hasScope("admin:accounts")).thenReturn(true);
        }

        @Test
        @DisplayName("a repeat under the same key adds no money and records nothing")
        void repeatUnderSameKeyAddsNoMoney() {
            alreadyPosted(KEY);

            service.credit(ACCOUNT_ID, posting(), null, KEY);

            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionService, never()).save(any());
        }

        @Test
        @DisplayName("the first posting under a key credits once")
        void firstPostingCredits() {
            service.credit(ACCOUNT_ID, posting(), null, KEY);

            assertThat(account.getBalance()).isEqualByComparingTo("575.00");
            assertThat(fingerprintOfSavedTransaction()).isEqualTo(KEY);
        }
    }

    @Nested
    @DisplayName("hold release")
    class Release {

        @BeforeEach
        void holdExists() {
            when(holdRepo.findById(HOLD_ID)).thenReturn(Optional.of(AccountHold.builder()
                    .id(HOLD_ID)
                    .accountId(ACCOUNT_ID)
                    .amount(new BigDecimal("75.00"))
                    .status(HoldStatus.ACTIVE)
                    .build()));
            when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        }

        @Test
        @DisplayName("the release records the key as its ledger fingerprint")
        void releaseRecordsKey() {
            service.releaseHold(ACCOUNT_ID, HOLD_ID, "settled", "payment-7f3a:release");

            assertThat(fingerprintOfSavedTransaction()).isEqualTo("payment-7f3a:release");
        }

        @Test
        @DisplayName("releasing a hold that is no longer active records nothing")
        void releasingInactiveHoldRecordsNothing() {
            // Already safe to repeat before this change: the hold's status is
            // what stops a second release, so no money moves either way.
            when(holdRepo.findById(HOLD_ID)).thenReturn(Optional.of(AccountHold.builder()
                    .id(HOLD_ID)
                    .accountId(ACCOUNT_ID)
                    .amount(new BigDecimal("75.00"))
                    .status(HoldStatus.RELEASED)
                    .build()));

            service.releaseHold(ACCOUNT_ID, HOLD_ID, "settled", "payment-7f3a:release");

            verify(transactionService, never()).save(any());
            verify(holdRepo, never()).save(any());
        }
    }

    @Test
    @DisplayName("a release and a debit for one payment use different keys, so both are applied")
    void releaseAndDebitDoNotShareAKey() {
        // Keys are unique per account. If the orchestrator sent one key for
        // both postings, the release would claim it and the debit would be
        // skipped as already applied, taking no money at all.
        when(holdRepo.findById(HOLD_ID)).thenReturn(Optional.of(AccountHold.builder()
                .id(HOLD_ID).accountId(ACCOUNT_ID).amount(new BigDecimal("75.00"))
                .status(HoldStatus.ACTIVE).build()));
        when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));

        service.releaseHold(ACCOUNT_ID, HOLD_ID, "settled", "payment-7f3a:release");
        service.debit(ACCOUNT_ID, posting(), null, "payment-7f3a:debit");

        assertThat(account.getBalance())
                .as("the debit must still happen after the release")
                .isEqualByComparingTo("425.00");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionService, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Transaction::getRequestFingerprint)
                .containsExactly("payment-7f3a:release", "payment-7f3a:debit");
    }

    @Test
    @DisplayName("one customer's key cannot match another customer's posting")
    void lookupIsScopedToTheAccount() {
        // The unique constraint and the lookup are both keyed on the account,
        // so an attacker reusing someone else's key finds nothing.
        service.debit(ACCOUNT_ID, posting(), null, KEY);

        verify(transactionService).findByAccountAndFingerprint(eq(ACCOUNT_ID), eq(KEY));
    }
}
