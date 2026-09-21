package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.dto.PostingRequest;
import com.account.dto.TransactionRequest;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.Transaction;
import com.account.model.TransactionType;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.account.repository.TransactionRepository;
import com.commons.security.CurrentUser;

/**
 * An Idempotency-Key used on one account must not stop another account's
 * posting from being recorded.
 *
 * <p>Idempotency is per account: the unique constraint is on
 * (accountId, requestFingerprint), and AccountService.alreadyPosted checks
 * within the account. TransactionService.save used to dedupe on the
 * fingerprint alone. When a second account posted under a key the first had
 * already used, its balance moved, save found the first account's row and
 * returned it, and the second account's ledger row was never written. A
 * retry then found nothing on the second account and moved the balance again.</p>
 *
 * <p>Every other posting test mocks TransactionService, which is why this
 * went unnoticed: the dedupe inside save never ran. Here the real
 * TransactionService runs against a repository that answers like the table
 * does. The ledger is a shared list, and any fingerprint query that does not
 * name the account searches every account's rows, exactly as the SQL would.
 * That rule is keyed on the query's name rather than on one method, so a
 * reintroduced unscoped lookup is caught here whatever it is called.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionFingerprintScopeTest {

    private static final UUID FIRST_ACCOUNT = UUID.randomUUID();
    private static final UUID SECOND_ACCOUNT = UUID.randomUUID();

    /** A key a naive client might well choose for itself. */
    private static final String SHARED_KEY = "retry-1";

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    /** Every row written to account_transaction, in order. */
    private final List<Transaction> ledger = new ArrayList<>();

    private TransactionService transactionService;
    private AccountService service;
    private Account first;
    private Account second;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(ledgerBackedRepository());

        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        first = account(FIRST_ACCOUNT, "cust-1");
        second = account(SECOND_ACCOUNT, "cust-2");

        when(accountRepo.findById(FIRST_ACCOUNT)).thenReturn(Optional.of(first));
        when(accountRepo.findById(SECOND_ACCOUNT)).thenReturn(Optional.of(second));
        when(accountRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(holdRepo.findByAccountIdAndStatus(any(), any())).thenReturn(List.of());

        when(mapper.toDto(any())).thenAnswer(call -> {
            Account a = call.getArgument(0);
            return new com.account.dto.AccountResponse(a.getId(), a.getCustomerId(), "12345678",
                    AccountType.CHEQUING, AccountSubType.PERSONAL, a.getStatus(), a.getCurrency(),
                    null, null, a.getBalance(), "****5678", a.getVersion());
        });

        // The mapper's one job that matters here: the row belongs to the
        // account the posting was made on.
        when(transactionMapper.toEntity(any())).thenAnswer(call -> {
            TransactionRequest request = call.getArgument(0);
            Transaction tx = new Transaction();
            tx.setAccountId(request.accountId());
            tx.setType(TransactionType.valueOf(request.type()));
            tx.setAmount(request.amount());
            tx.setBalanceAfter(request.balanceAfter());
            return tx;
        });

        // A trusted service caller, as the Payment Orchestrator is, so both
        // accounts can be posted to; ownership is covered elsewhere.
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    private static Account account(UUID id, String customerId) {
        return Account.builder()
                .id(id)
                .customerId(customerId)
                .currency("CAD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();
    }

    /**
     * A TransactionRepository that answers fingerprint queries the way
     * Postgres would. A query naming the account filters on it; one that does
     * not searches every account's rows.
     */
    private TransactionRepository ledgerBackedRepository() {
        return mock(TransactionRepository.class, invocation -> {
            String name = invocation.getMethod().getName();

            if (name.equals("save")) {
                Transaction tx = invocation.getArgument(0);
                ledger.add(tx);
                return tx;
            }

            if (name.startsWith("findBy") && name.contains("RequestFingerprint")) {
                boolean scopedToAccount = name.contains("AccountId");
                UUID accountId = scopedToAccount ? invocation.getArgument(0) : null;
                String fingerprint = invocation.getArgument(scopedToAccount ? 1 : 0);

                return ledger.stream()
                        .filter(tx -> fingerprint.equals(tx.getRequestFingerprint()))
                        .filter(tx -> !scopedToAccount || accountId.equals(tx.getAccountId()))
                        .findFirst();
            }

            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private List<Transaction> rowsFor(UUID accountId) {
        return ledger.stream().filter(tx -> accountId.equals(tx.getAccountId())).toList();
    }

    private static PostingRequest posting() {
        return new PostingRequest(new BigDecimal("75.00"), "test");
    }

    @Test
    @DisplayName("a key already used on another account does not stop this account's credit being recorded")
    void creditIsRecordedDespiteAnotherAccountsKey() {

        service.credit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);
        service.credit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);

        assertThat(rowsFor(SECOND_ACCOUNT))
                .as("the second account's balance moved, so its ledger must say so")
                .hasSize(1)
                .first()
                .satisfies(tx -> {
                    assertThat(tx.getRequestFingerprint()).isEqualTo(SHARED_KEY);
                    assertThat(tx.getBalanceAfter()).isEqualByComparingTo("575.00");
                });
        assertThat(rowsFor(FIRST_ACCOUNT)).hasSize(1);
    }

    @Test
    @DisplayName("retrying that credit on the second account moves its balance once")
    void retriedCreditMovesTheBalanceOnce() {

        service.credit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);

        service.credit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);
        service.credit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);

        assertThat(second.getBalance())
                .as("a retry under the same key must not credit the account twice")
                .isEqualByComparingTo("575.00");
        assertThat(rowsFor(SECOND_ACCOUNT)).hasSize(1);
    }

    @Test
    @DisplayName("retrying a debit on the second account takes the money once")
    void retriedDebitTakesTheMoneyOnce() {

        service.debit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);

        service.debit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);
        service.debit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);

        assertThat(second.getBalance())
                .as("a retry under the same key must not debit the account twice")
                .isEqualByComparingTo("425.00");
        assertThat(rowsFor(SECOND_ACCOUNT)).hasSize(1);
    }

    @Test
    @DisplayName("the ledger explains each account's balance")
    void ledgerExplainsTheBalance() {

        service.credit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);
        service.debit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);
        service.debit(SECOND_ACCOUNT, posting(), null, SHARED_KEY);

        for (Account account : List.of(first, second)) {
            BigDecimal net = rowsFor(account.getId()).stream()
                    .map(tx -> tx.getType() == TransactionType.DEBIT ? tx.getAmount().negate() : tx.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(new BigDecimal("500.00").add(net))
                    .as("opening balance plus the ledger must equal the balance of " + account.getCustomerId())
                    .isEqualByComparingTo(account.getBalance());
        }
    }

    @Test
    @DisplayName("a repeat on the same account is still recorded once")
    void repeatOnTheSameAccountIsStillDeduplicated() {

        // The control for the tests above: two postings on one account under
        // one key still land once. Note this is held by alreadyPosted, which
        // stops the repeat before save runs; the tests below pin save itself.
        service.credit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);
        service.credit(FIRST_ACCOUNT, posting(), null, SHARED_KEY);

        assertThat(first.getBalance()).isEqualByComparingTo("575.00");
        assertThat(rowsFor(FIRST_ACCOUNT)).hasSize(1);
    }
    private static Transaction row(UUID accountId, String fingerprint) {
        Transaction tx = new Transaction();
        tx.setAccountId(accountId);
        tx.setRequestFingerprint(fingerprint);
        return tx;
    }

    @Test
    @DisplayName("save returns the earlier row for a key already recorded on the same account")
    void saveDedupesWithinTheAccount() {

        // save is the last line of defence for a posting that reaches it
        // without passing alreadyPosted, so its own dedupe is pinned here
        // rather than inferred from the flows above, which never exercise it.
        Transaction original = transactionService.save(row(FIRST_ACCOUNT, SHARED_KEY));

        Transaction repeat = transactionService.save(row(FIRST_ACCOUNT, SHARED_KEY));

        assertThat(repeat).isSameAs(original);
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("save records a row whose key is already recorded only on another account")
    void saveDoesNotDedupeAcrossAccounts() {

        transactionService.save(row(FIRST_ACCOUNT, SHARED_KEY));

        Transaction other = transactionService.save(row(SECOND_ACCOUNT, SHARED_KEY));

        assertThat(other.getAccountId()).isEqualTo(SECOND_ACCOUNT);
        assertThat(ledger).hasSize(2);
    }
}
