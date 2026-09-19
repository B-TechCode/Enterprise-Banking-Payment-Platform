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
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.account.dto.AccountRequest;
import com.account.dto.AccountResponse;
import com.account.dto.AccountStatus;
import com.account.dto.AccountSubType;
import com.account.dto.AccountType;
import com.account.dto.CreateHoldRequest;
import com.account.dto.HoldStatus;
import com.account.dto.PostingRequest;
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
 * Protects the ownership fix from commit 6feb81e, which closed an IDOR that let
 * any authenticated customer read and act on other customers' accounts.
 *
 * <p>Access is decided in three steps: an admin scope passes; otherwise a
 * client-credentials token carrying no customer identity passes (a trusted
 * service such as the Payment Orchestrator); otherwise the caller must present
 * a customer_id equal to the account's owner.</p>
 *
 * <p>The rule exists twice in AccountService, once keyed on a loaded account
 * and once keyed on a customer id, so both copies are exercised here: one could
 * drift from the other without either breaking the build.</p>
 *
 * <p>The identity signals themselves (grant type, customer_id claim) are
 * derived from the token by CurrentUser and pinned by CurrentUserTest. Here
 * CurrentUser is mocked, so each case states exactly which signals the caller
 * presents.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceOwnershipTest {

    private static final String OWNER = "cust-owner";
    private static final String OTHER = "cust-other";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID HOLD_ID = UUID.randomUUID();

    @Mock private AccountRepository accountRepo;
    @Mock private AccountHoldRepository holdRepo;
    @Mock private AccountMapper mapper;
    @Mock private TransactionService transactionService;
    @Mock private TransactionMapper transactionMapper;
    @Mock private CurrentUser currentUser;

    private AccountService service;
    private Account account;
    private AccountResponse ownersView;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepo, holdRepo, mapper, transactionService, transactionMapper, currentUser);

        account = Account.builder()
                .id(ACCOUNT_ID)
                .customerId(OWNER)
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("500.00"))
                .version(1)
                .build();

        ownersView = new AccountResponse(ACCOUNT_ID, OWNER, "12345678", AccountType.CHEQUING,
                AccountSubType.PERSONAL, AccountStatus.ACTIVE, "USD", null, "Everyday",
                new BigDecimal("500.00"), "****5678", 1);

        when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepo.findByCustomerId(OWNER)).thenReturn(List.of(account));
        when(mapper.toDto(account)).thenReturn(ownersView);

        when(holdRepo.findById(HOLD_ID)).thenReturn(Optional.of(AccountHold.builder()
                .id(HOLD_ID)
                .accountId(ACCOUNT_ID)
                .amount(new BigDecimal("50.00"))
                .status(HoldStatus.ACTIVE)
                .build()));
        when(holdRepo.findByAccountIdAndStatus(ACCOUNT_ID, HoldStatus.ACTIVE)).thenReturn(List.of());
        when(holdRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(accountRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(transactionMapper.toEntity(any())).thenReturn(new Transaction());
    }

    // ------------------------------------------------------------ callers

    /** An end user whose token carries this customer_id. */
    private void callerIsCustomer(String customerId) {
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(customerId));
    }

    /** A trusted service: client-credentials grant, no customer identity. */
    private void callerIsService() {
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    /** A user token that carries no customer_id at all. */
    private void callerIsUserWithoutCustomerId() {
        when(currentUser.isClientCredentials()).thenReturn(false);
        when(currentUser.customerIdClaim()).thenReturn(Optional.empty());
    }

    /** A client-credentials token that nevertheless asserts a customer. */
    private void callerIsServiceClaimingCustomer(String customerId) {
        when(currentUser.isClientCredentials()).thenReturn(true);
        when(currentUser.customerIdClaim()).thenReturn(Optional.of(customerId));
    }

    private void callerHasScope(String scope) {
        when(currentUser.hasScope(scope)).thenReturn(true);
    }

    private void verifyNothingWritten() {
        verify(accountRepo, never()).save(any());
        verify(accountRepo, never()).saveAndFlush(any());
        verify(holdRepo, never()).save(any());
        verify(transactionService, never()).save(any());
    }

    // --------------------------------------------- account-keyed ownership

    @Nested
    @DisplayName("access to an account (check keyed on the loaded account)")
    class AccountKeyed {

        @Test
        @DisplayName("the owner can read their own account")
        void ownerAllowed() {
            callerIsCustomer(OWNER);

            assertThat(service.get(ACCOUNT_ID)).isEqualTo(ownersView);
        }

        @Test
        @DisplayName("another customer is denied")
        void nonOwnerDenied() {
            callerIsCustomer(OTHER);

            assertThatThrownBy(() -> service.get(ACCOUNT_ID))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("a client-credentials service with no customer identity is let through")
        void serviceBypassAllowed() {
            callerIsService();

            assertThat(service.get(ACCOUNT_ID)).isEqualTo(ownersView);
        }

        @Test
        @DisplayName("a user token with no customer_id is denied: no identity without the grant type")
        void userWithoutCustomerIdDenied() {
            // The bypass needs the client-credentials grant as well as the
            // absence of a customer. A user token that merely lacks the claim
            // must not be mistaken for a service.
            callerIsUserWithoutCustomerId();

            assertThatThrownBy(() -> service.get(ACCOUNT_ID))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("a client-credentials token that asserts a customer is held to ownership")
        void serviceClaimingAnotherCustomerDenied() {
            // Carrying customer_id removes the bypass: the token is then judged
            // as that customer, and this account is not theirs.
            callerIsServiceClaimingCustomer(OTHER);

            assertThatThrownBy(() -> service.get(ACCOUNT_ID))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("holding fdx:accounts.write does not bypass ownership")
        void writeScopeDoesNotBypass() {
            // The bypass is deliberately keyed on grant type, not scope. Keying
            // it on this scope would exempt every user token that holds it.
            callerIsCustomer(OTHER);
            callerHasScope("fdx:accounts.write");

            assertThatThrownBy(() -> service.get(ACCOUNT_ID))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("an administrator can read any account")
        void adminAllowed() {
            callerIsCustomer(OTHER);
            callerHasScope("admin:accounts");

            assertThat(service.get(ACCOUNT_ID)).isEqualTo(ownersView);
        }
    }

    // -------------------------------------------- customer-keyed ownership

    @Nested
    @DisplayName("listing a customer's accounts (check keyed on the customer id)")
    class CustomerKeyed {

        @Test
        @DisplayName("the owner can list their own accounts")
        void ownerAllowed() {
            callerIsCustomer(OWNER);

            assertThat(service.findByCustomerId(OWNER)).containsExactly(ownersView);
        }

        @Test
        @DisplayName("another customer is denied before the repository is queried")
        void nonOwnerDenied() {
            callerIsCustomer(OTHER);

            assertThatThrownBy(() -> service.findByCustomerId(OWNER))
                    .isInstanceOf(OwnerAccessDeniedException.class);

            verify(accountRepo, never()).findByCustomerId(anyString());
        }

        @Test
        @DisplayName("a client-credentials service with no customer identity is let through")
        void serviceBypassAllowed() {
            callerIsService();

            assertThat(service.findByCustomerId(OWNER)).containsExactly(ownersView);
        }

        @Test
        @DisplayName("a user token with no customer_id is denied")
        void userWithoutCustomerIdDenied() {
            callerIsUserWithoutCustomerId();

            assertThatThrownBy(() -> service.findByCustomerId(OWNER))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("a client-credentials token that asserts a customer is held to ownership")
        void serviceClaimingAnotherCustomerDenied() {
            callerIsServiceClaimingCustomer(OTHER);

            assertThatThrownBy(() -> service.findByCustomerId(OWNER))
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }
    }

    // ------------------------------------------------------ admin-only

    @Nested
    @DisplayName("listing every account (administrators only)")
    class AdminOnly {

        @Test
        @DisplayName("an ordinary customer is denied")
        void customerDenied() {
            callerIsCustomer(OWNER);

            assertThatThrownBy(() -> service.listAll())
                    .isInstanceOf(OwnerAccessDeniedException.class);
            verify(accountRepo, never()).findAll();
        }

        @Test
        @DisplayName("a client-credentials service is denied: no service needs every account")
        void serviceDenied() {
            callerIsService();

            assertThatThrownBy(() -> service.listAll())
                    .isInstanceOf(OwnerAccessDeniedException.class);
        }

        @Test
        @DisplayName("admin:accounts and admin:accounts.read are each sufficient")
        void adminScopesAllowed() {
            when(accountRepo.findAll()).thenReturn(List.of(account));

            callerHasScope("admin:accounts.read");
            assertThat(service.listAll()).containsExactly(ownersView);

            when(currentUser.hasScope("admin:accounts.read")).thenReturn(false);
            callerHasScope("admin:accounts");
            assertThat(service.listAll()).containsExactly(ownersView);
        }
    }

    // ------------------------------------- every operation is guarded

    /**
     * Each account operation, as called by a customer who does not own the
     * account. Listed so that a new operation, or a guard removed from an
     * existing one, has to be looked at here.
     */
    static Stream<Named<AccountOperation>> operationsOnSomeoneElsesAccount() {
        return Stream.of(
                Named.of("getBalance", s -> s.getBalance(ACCOUNT_ID)),
                Named.of("getCustomerIdForAccount", s -> s.getCustomerIdForAccount(ACCOUNT_ID)),
                Named.of("updateStatus", s -> s.updateStatus(ACCOUNT_ID, AccountStatus.CLOSED)),
                Named.of("credit", s -> s.credit(ACCOUNT_ID,
                        new PostingRequest(new BigDecimal("10.00"), "test"), null)),
                Named.of("debit", s -> s.debit(ACCOUNT_ID,
                        new PostingRequest(new BigDecimal("10.00"), "test"), null)),
                Named.of("createHold", s -> s.createHold(ACCOUNT_ID,
                        new CreateHoldRequest(new BigDecimal("10.00"), "test", null, null))),
                Named.of("releaseHold", s -> s.releaseHold(ACCOUNT_ID, HOLD_ID, "test")));
    }

    @FunctionalInterface
    interface AccountOperation {
        void run(AccountService service) throws Throwable;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsOnSomeoneElsesAccount")
    @DisplayName("every account operation refuses a non-owner and writes nothing")
    void nonOwnerIsRefusedEverywhere(AccountOperation operation) {
        callerIsCustomer(OTHER);

        assertThatThrownBy(() -> operation.run(service))
                .isInstanceOf(OwnerAccessDeniedException.class);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("creating an account for another customer is refused")
    void createForAnotherCustomerRefused() {
        callerIsCustomer(OTHER);
        AccountRequest request = new AccountRequest(OWNER, AccountType.CHEQUING,
                AccountSubType.PERSONAL, AccountStatus.ACTIVE, "USD", null, "Everyday", null);
        when(accountRepo.findByRequestFingerprint(anyString())).thenReturn(Optional.empty());
        when(mapper.toEntity(request)).thenReturn(Account.builder().customerId(OWNER).build());

        assertThatThrownBy(() -> service.create(request, "key-1"))
                .isInstanceOf(OwnerAccessDeniedException.class);
        verifyNothingWritten();
    }

    // ------------------------------------------------ positive controls

    @Test
    @DisplayName("the owner can release a hold on their own account")
    void ownerCanReleaseHold() {
        // Positive control for the releaseHold denial above: proves that path
        // can succeed, so its refusal is the guard and not a broken fixture.
        callerIsCustomer(OWNER);

        assertThat(service.releaseHold(ACCOUNT_ID, HOLD_ID, "paid").status())
                .isEqualTo(HoldStatus.RELEASED);
        verify(holdRepo).save(any());
    }

    @Test
    @DisplayName("a client-credentials service can place a hold (the Payment Orchestrator's path)")
    void serviceCanPlaceHold() {
        // The bypass exists for this call: the orchestrator reserves funds on
        // a customer's account while executing a payment.
        callerIsService();

        assertThat(service.createHold(ACCOUNT_ID,
                new CreateHoldRequest(new BigDecimal("25.00"), "billpay", null, null)).status())
                .isEqualTo(HoldStatus.ACTIVE);
        verify(holdRepo).save(any());
    }
}
