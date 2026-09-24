package com.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.prepost.PreAuthorize;

import com.account.controller.AccountController;
import com.account.dto.AccountRequest;
import com.account.dto.AccountResponse;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.dto.PostingRequest;
import com.account.mapper.AccountMapper;
import com.account.mapper.TransactionMapper;
import com.account.model.Account;
import com.account.model.Transaction;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.exception.OwnerAccessDeniedException;
import com.commons.security.CurrentUser;

/**
 * Only an administrator may bring money into the platform.
 *
 * <p>Crediting an account and opening one with a balance are the only two ways a
 * balance appears from nothing. The platform has no deposit, transfer or
 * external funding domain: a credit cannot name a source, and the ledger row has
 * no field to record one in - it carries an account, an amount, a free-text
 * reason and a timestamp. Nothing in the platform calls credit either; it exists
 * to provision demo accounts.</p>
 *
 * <p>Both paths used to admit the account's owner, so a customer could
 * fabricate money into their own account, with the ledger recording only that
 * they had. Restricting credit alone would have achieved nothing while
 * openingBalance stayed open, hence both here.</p>
 *
 * <p>The service check and the endpoint's scope are tested together. Either
 * alone is one edit away from being the only guard.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountFundingAuthorizationTest {

    private static final String CUSTOMER = "cust-1";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

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
                .balance(new BigDecimal("100.00"))
                .version(1)
                .build();

        when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(accountRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(accountRepo.findByCustomerIdAndRequestFingerprint(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(holdRepo.findByAccountIdAndStatus(any(), any())).thenReturn(List.of());
        when(transactionMapper.toEntity(any())).thenAnswer(call -> new Transaction());
        when(transactionService.findByAccountAndFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());
        when(mapper.toEntity(any(AccountRequest.class))).thenAnswer(call -> {
            AccountRequest r = call.getArgument(0);
            return Account.builder().customerId(r.customerId()).build();
        });
        when(mapper.toDto(any())).thenAnswer(call -> {
            Account a = call.getArgument(0);
            return new AccountResponse(a.getId(), a.getCustomerId(), "12345678",
                    AccountType.CHEQUING, AccountSubType.PERSONAL, AccountStatus.ACTIVE, "CAD",
                    null, null, a.getBalance(), "****5678", a.getVersion());
        });
    }

    /** The account's own customer, holding no administrative scope. */
    private void callerIsTheOwner() {
        when(currentUser.hasScope("admin:accounts")).thenReturn(false);
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(CUSTOMER));
    }

    private void callerIsAnAdministrator() {
        when(currentUser.hasScope("admin:accounts")).thenReturn(true);
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    /** A trusted service: allowed to move money, not to create it. */
    private void callerIsAService() {
        when(currentUser.hasScope("admin:accounts")).thenReturn(false);
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    private static AccountRequest request(BigDecimal openingBalance) {
        return new AccountRequest(CUSTOMER, AccountType.CHEQUING, AccountSubType.PERSONAL,
                AccountStatus.ACTIVE, "CAD", "Main", "Everyday", openingBalance);
    }

    @Nested
    @DisplayName("crediting an account")
    class Crediting {

        private PostingRequest posting() {
            return new PostingRequest(new BigDecimal("500.00"), "demo funding");
        }

        @Test
        @DisplayName("the account's own customer is refused, and no money appears")
        void ownerRefused() {
            callerIsTheOwner();

            assertThatThrownBy(() -> service.credit(ACCOUNT_ID, posting(), null, "key-1"))
                    .isInstanceOf(OwnerAccessDeniedException.class);

            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
            verify(accountRepo, never()).saveAndFlush(any());
            verify(transactionService, never()).save(any());
        }

        @Test
        @DisplayName("the refusal happens before the account is even read")
        void ownerRefusedBeforeAnyLookup() {
            // So a refusal cannot double as an answer to "does this account
            // exist?" for someone probing ids.
            callerIsTheOwner();

            assertThatThrownBy(() -> service.credit(UUID.randomUUID(), posting(), null, null))
                    .isInstanceOf(OwnerAccessDeniedException.class);

            verify(accountRepo, never()).findById(any());
        }

        @Test
        @DisplayName("a service token is refused too: services move money, they do not create it")
        void serviceRefused() {
            // The service bypass exists for holds and settlement debits. Nothing
            // in the platform credits an account, so this path has no caller to
            // serve and stays shut.
            callerIsAService();

            assertThatThrownBy(() -> service.credit(ACCOUNT_ID, posting(), null, "key-1"))
                    .isInstanceOf(OwnerAccessDeniedException.class);

            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("an administrator can still fund an account")
        void administratorAllowed() {
            // Positive control: the refusals above are the guard, not a broken
            // fixture. This is how a demo account gets its money.
            callerIsAnAdministrator();

            var response = service.credit(ACCOUNT_ID, posting(), null, "key-1");

            assertThat(response.balance()).isEqualByComparingTo("600.00");
            assertThat(account.getBalance()).isEqualByComparingTo("600.00");
            verify(transactionService).save(any());
        }
    }

    @Nested
    @DisplayName("opening an account with a balance")
    class OpeningBalance {

        @Test
        @DisplayName("a customer opening an account with money in it is refused")
        void customerRefusedAnOpeningBalance() {
            // The same fabrication as a credit, in one call. Restricting credit
            // alone would have left this wide open.
            callerIsTheOwner();

            assertThatThrownBy(() -> service.create(request(new BigDecimal("5000.00")), "key-1"))
                    .isInstanceOf(OwnerAccessDeniedException.class);

            verify(accountRepo, never()).save(any());
        }

        @Test
        @DisplayName("a customer may still open an account at zero")
        void customerMayOpenAtZero() {
            callerIsTheOwner();

            var response = service.create(request(BigDecimal.ZERO), "key-1");

            assertThat(response.balance()).isEqualByComparingTo("0.00");
            verify(accountRepo).save(any());
        }

        @Test
        @DisplayName("a customer omitting the opening balance is unaffected")
        void customerMayOmitOpeningBalance() {
            callerIsTheOwner();

            var response = service.create(request(null), "key-1");

            assertThat(response.balance()).isEqualByComparingTo("0.00");
            verify(accountRepo).save(any());
        }

        @Test
        @DisplayName("an administrator may open an account with a balance")
        void administratorMayOpenWithABalance() {
            callerIsAnAdministrator();

            var response = service.create(request(new BigDecimal("5000.00")), "key-1");

            assertThat(response.balance()).isEqualByComparingTo("5000.00");
            verify(accountRepo).save(any());
        }
    }

    @Nested
    @DisplayName("the endpoint's own scope")
    class EndpointScope {

        /**
         * The service check above runs inside the application. This asserts the
         * rule is also enforced at the door, since the two are independent: a
         * re-annotated controller would leave the service check as the only
         * guard, and vice versa.
         */
        @Test
        @DisplayName("credit requires an administrative scope, and no customer scope opens it")
        void creditIsAdministrativeOnly() throws Exception {
            Method credit = AccountController.class.getMethod("credit",
                    UUID.class, String.class, String.class, PostingRequest.class);

            String expression = credit.getAnnotation(PreAuthorize.class).value();

            assertThat(expression)
                    .as("a customer scope here would let an account owner fabricate money again")
                    .doesNotContain("fdx:")
                    .contains("admin:accounts");
        }
    }
}
