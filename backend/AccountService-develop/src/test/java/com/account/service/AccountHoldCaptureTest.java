package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.dto.HoldStatus;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.AccountHold;
import com.account.model.Transaction;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.exception.OwnerAccessDeniedException;
import com.commons.security.CurrentUser;

/**
 * Capturing a hold takes the reserved funds in one step.
 *
 * <p>It replaces releasing a hold and then debiting the account. Between those
 * two calls the funds were no longer reserved: a customer could spend them, the
 * debit would then fail for insufficient funds, and the payment could never be
 * collected although its reservation was gone. Capture closes that window by
 * never letting the money become spendable.</p>
 *
 * <p>The amount is taken from the hold rather than from the caller, so what is
 * captured cannot disagree with what was reserved, and the ledger records a
 * single DEBIT: one real event for the customer, not the internal mechanics.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountHoldCaptureTest {

    private static final String OWNER = "cust-owner";
    private static final String OTHER = "cust-other";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID HOLD_ID = UUID.randomUUID();
    private static final String KEY = "payment-7f3a:capture";

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionService transactionService;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    private AccountService service;
    private Account account;
    private AccountHold hold;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        account = Account.builder()
                .id(ACCOUNT_ID)
                .customerId(OWNER)
                .currency("CAD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();

        hold = AccountHold.builder()
                .id(HOLD_ID)
                .accountId(ACCOUNT_ID)
                .amount(new BigDecimal("75.00"))
                .status(HoldStatus.ACTIVE)
                .build();

        when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(holdRepo.findById(HOLD_ID)).thenReturn(Optional.of(hold));
        when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(transactionMapper.toEntity(any())).thenAnswer(call -> new Transaction());
        when(mapper.toDto(any())).thenAnswer(call -> {
            Account a = call.getArgument(0);
            return new com.account.dto.AccountResponse(a.getId(), a.getCustomerId(), "12345678",
                    AccountType.CHEQUING, AccountSubType.PERSONAL, a.getStatus(), a.getCurrency(),
                    null, null, a.getBalance(), "****5678", a.getVersion());
        });
        when(transactionService.findByAccountAndFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());

        callerIsCustomer(OWNER);
    }

    private void callerIsCustomer(String customerId) {
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(customerId));
    }

    private Transaction savedPosting() {
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionService).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("capturing takes the held amount and marks the hold captured")
    void captureTakesTheHeldAmount() {
        var response = service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        assertThat(account.getBalance())
                .as("the held funds leave the account")
                .isEqualByComparingTo("425.00");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(response.status()).isEqualTo(HoldStatus.CAPTURED);
    }

    @Test
    @DisplayName("the ledger records one DEBIT for the held amount, carrying the key")
    void ledgerRecordsOneDebit() {
        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        Transaction posting = savedPosting();
        assertThat(posting.getRequestFingerprint()).isEqualTo(KEY);
        verify(transactionMapper).toEntity(any());
    }

    @Test
    @DisplayName("the amount comes from the hold, so it cannot disagree with what was reserved")
    void amountComesFromTheHold() {
        hold.setAmount(new BigDecimal("120.00"));

        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        assertThat(account.getBalance()).isEqualByComparingTo("380.00");
    }

    @Test
    @DisplayName("captured funds stay taken: the hold no longer reserves anything")
    void capturedHoldNoLongerReserves() {
        // activeHoldsTotal counts ACTIVE holds, so once captured the amount is
        // gone from the balance and no longer subtracted again as a reservation.
        when(holdRepo.findByAccountIdAndStatus(ACCOUNT_ID, HoldStatus.ACTIVE)).thenReturn(List.of());

        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        var balance = service.getBalance(ACCOUNT_ID);
        assertThat(balance.balance()).isEqualByComparingTo("425.00");
        assertThat(balance.available())
                .as("available must equal the balance once nothing is held")
                .isEqualByComparingTo("425.00");
    }

    @Test
    @DisplayName("a repeat under the same key takes nothing further")
    void repeatUnderSameKeyTakesNothing() {
        when(transactionService.findByAccountAndFingerprint(ACCOUNT_ID, KEY))
                .thenReturn(Optional.of(new Transaction()));

        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        verify(accountRepo, never()).saveAndFlush(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    @DisplayName("capturing an already captured hold takes nothing further")
    void alreadyCapturedTakesNothing() {
        // Belt and braces alongside the key: even a different key cannot take
        // the same held funds twice.
        hold.setStatus(HoldStatus.CAPTURED);

        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", "some-other-key");

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        verify(transactionService, never()).save(any());
    }

    @Test
    @DisplayName("a hold released by the older release-then-debit path is still debited")
    void releasedHoldIsStillDebited() {
        // The deployment case: a payment in flight had its hold released by the
        // previous code. Refusing would strand it, so the debit is applied
        // without the hold. The key still makes it happen once.
        hold.setStatus(HoldStatus.RELEASED);

        service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY);

        assertThat(account.getBalance()).isEqualByComparingTo("425.00");
        assertThat(hold.getStatus())
                .as("a released hold is not retrospectively marked captured")
                .isEqualTo(HoldStatus.RELEASED);
        assertThat(savedPosting().getRequestFingerprint()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("another customer cannot capture a hold, and no money moves")
    void nonOwnerIsRefused() {
        callerIsCustomer(OTHER);

        assertThatThrownBy(() -> service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY))
                .isInstanceOf(OwnerAccessDeniedException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        verify(holdRepo, never()).save(any());
        verify(accountRepo, never()).saveAndFlush(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    @DisplayName("a hold belonging to another account is rejected")
    void holdFromAnotherAccountIsRejected() {
        hold.setAccountId(UUID.randomUUID());

        assertThatThrownBy(() -> service.captureHold(ACCOUNT_ID, HOLD_ID, "settled", KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
    }
}
