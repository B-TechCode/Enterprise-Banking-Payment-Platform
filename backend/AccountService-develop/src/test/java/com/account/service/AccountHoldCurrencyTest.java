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
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.AccountHold;
import com.account.model.Transaction;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.exception.CurrencyMismatchException;
import com.commons.security.CurrentUser;

/**
 * A hold is refused when it is stated in a currency the account is not held in.
 *
 * <p>Nothing below the payment record carried a currency, so a CAD payment drawn
 * on a USD account passed validation, placed a hold on the USD account for that
 * number, and was debited at an implied rate of 1:1. No conversion happened
 * anywhere because there was nothing to convert between - the second currency
 * never reached the service that knew the first.</p>
 *
 * <p>The check sits in {@code createHold} rather than in the payment
 * orchestrator because this service owns the account and already has it loaded.
 * Every caller of the hold endpoint is covered, not only the payment path that
 * prompted the fix.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountHoldCurrencyTest {

    private static final String CUSTOMER = "cust-1";
    private static final UUID ACCOUNT = UUID.randomUUID();

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

        Account usdAccount = Account.builder()
                .id(ACCOUNT)
                .customerId(CUSTOMER)
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();

        when(accountRepo.findById(ACCOUNT)).thenReturn(Optional.of(usdAccount));
        when(holdRepo.findByAccountIdAndStatus(any(), any())).thenReturn(List.of());
        when(holdRepo.findByAccountIdAndRequestFingerprint(eq(ACCOUNT), anyString()))
                .thenReturn(Optional.empty());
        when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(transactionMapper.toEntity(any())).thenAnswer(call -> new Transaction());

        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(CUSTOMER));
    }

    private CreateHoldRequest requestIn(String currency) {
        return new CreateHoldRequest(
                new BigDecimal("25.00"), currency, "billpay", (LocalDateTime) null, "key-1");
    }

    @Test
    @DisplayName("a hold in the account's own currency is placed")
    void matchingCurrencyIsAccepted() {
        assertThat(service.createHold(ACCOUNT, requestIn("USD"))).isNotNull();
        verify(holdRepo).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("a hold in another currency is refused")
    void mismatchedCurrencyIsRefused() {
        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn("CAD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("the refusal names both currencies, so a caller knows what to correct")
    void refusalNamesBothCurrencies() {
        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn("CAD")))
                .hasMessageContaining("CAD")
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("nothing is reserved or recorded when the currency does not match")
    void mismatchReservesNothing() {
        // The check has to happen before the write, not alongside it. A hold
        // row written and then rolled back is still a row the balance was
        // computed against for the length of the transaction.
        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn("CAD")))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(holdRepo, never()).save(any(AccountHold.class));
        verify(transactionService, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("a mismatched currency is reported ahead of insufficient funds")
    void currencyIsCheckedBeforeAvailableFunds() {
        // Order matters, and only this asserts it. If the guard sits below the
        // available-funds computation, a caller paying the wrong currency out
        // of an overdrawn account is told they are short of money - which is
        // true, irrelevant, and sends them to top up an account that would
        // still refuse the payment.
        when(accountRepo.findById(ACCOUNT)).thenReturn(Optional.of(Account.builder()
                .id(ACCOUNT)
                .customerId(CUSTOMER)
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("1.00"))
                .version(1)
                .build()));

        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn("CAD")))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("CAD");
    }

    @Test
    @DisplayName("currency is compared exactly, not loosely")
    void comparisonIsExact() {
        // "usd" is not "USD". The controller rejects a lowercase value by
        // pattern before it reaches here, but this service is also called
        // in-process, so the comparison must not quietly normalise.
        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn("usd")))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(holdRepo, never()).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("an absent currency is refused rather than assumed")
    void missingCurrencyIsRefused() {
        // Defaulting a missing currency to the account's own would make the
        // field optional in practice and put the 1:1 debit back within reach of
        // any caller that simply omitted it.
        assertThatThrownBy(() -> service.createHold(ACCOUNT, requestIn(null)))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(holdRepo, never()).save(any(AccountHold.class));
    }
}
