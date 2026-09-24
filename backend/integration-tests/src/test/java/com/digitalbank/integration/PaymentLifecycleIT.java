package com.digitalbank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The bill payment lifecycle, end to end, through the real services.
 *
 * <p>A customer opens an account, funds it, registers a biller and pays a bill.
 * The payment then travels the whole asynchronous path: funds are held, the
 * outbox publishes the request, the worker batches it, settlement uploads the
 * batch and reports it submitted, and the settlement confirmation debits the
 * account and releases the hold. Every step happens in a separate service,
 * over HTTP and Kafka, against a real database.</p>
 *
 * <p>Balances are asserted at the end because they are the part a customer
 * would notice: the money must leave the account exactly once, and the hold
 * that reserved it must be gone.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentLifecycleIT {

    private static final BigDecimal OPENING_BALANCE = new BigDecimal("500.00");
    private static final BigDecimal BILL_AMOUNT = new BigDecimal("75.00");
    private static final String IDEMPOTENCY_KEY = "integration-test-" + UUID.randomUUID();

    private static PlatformStack stack;
    private static String customerId;
    private static String customerToken;

    /**
     * An operator provisioning demo money. Only an administrator may credit an
     * account or open one with a balance: the platform has no deposit or
     * transfer domain, so a credit cannot say where the money came from.
     */
    private static String adminToken;

    private static UUID accountId;
    private static String billerReference;
    private static UUID paymentId;

    @BeforeAll
    static void startPlatform() throws Exception {
        // The JDBC driver sends this JVM's timezone when it connects, and
        // Postgres rejects the "Asia/Calcutta" alias. The services force UTC in
        // their own main() for the same reason; this test connects directly, so
        // it has to do the same.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        stack = new PlatformStack();
        stack.start();

        customerId = "cust-" + UUID.randomUUID();
        customerToken = stack.identity().userToken(
                customerId, IdentityProviderStub.allUserScopes().toArray(String[]::new));

        adminToken = stack.identity().userToken(
                "ops-" + UUID.randomUUID(), "admin:accounts", "fdx:accounts.read");
    }

    @AfterAll
    static void stopPlatform() {
        if (stack != null) {
            stack.close();
        }
    }

    private static String accounts() {
        return stack.urlOf("account-service", 8084) + "/api/v1";
    }

    private static String billers() {
        return stack.urlOf("biller-service", 8088) + "/api/v1";
    }

    private static String payments() {
        return stack.urlOf("payment-orchestrator", 8086) + "/api/v1";
    }

    private static String worker() {
        return stack.urlOf("billpay-worker-service", 8090) + "/api/mock/central1";
    }

    @Test
    @Order(1)
    @DisplayName("a bill payment reaches POSTED: funds held, batched, settled, debited, hold released")
    void paymentLifecycle() {
        // --- the customer registers a biller -------------------------------
        billerReference = "HYDRO-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode biller = Rest.post(billers() + "/billers", customerToken, """
                {"name":"City Hydro","referenceNumber":"%s","category":"Electricity"}
                """.formatted(billerReference)).require(201);
        assertThat(biller.get("status").asText()).isEqualTo("ACTIVE");

        // --- the customer opens an account and funds it ---------------------
        JsonNode account = Rest.post(accounts() + "/accounts", customerToken, """
                {"customerId":"%s","accountType":"CHEQUING","accountSubType":"PERSONAL",
                 "status":"ACTIVE","currency":"CAD","nickname":"Everyday",
                 "displayName":"Everyday Chequing","openingBalance":0}
                """.formatted(customerId)).require(201);
        accountId = UUID.fromString(account.get("id").asText());

        // Funded by an operator, not by the customer: money entering the
        // platform is administrative, since nothing here can say where it came
        // from. A posting is a created resource, so Account Service answers 201.
        Rest.post(accounts() + "/accounts/" + accountId + "/credit", adminToken, """
                {"amount":%s,"reason":"demo funding"}
                """.formatted(OPENING_BALANCE)).require(201);

        assertThat(balance()).isEqualByComparingTo(OPENING_BALANCE);

        // --- the customer pays a bill --------------------------------------
        JsonNode accepted = Rest.post(payments() + "/payments/billpay", customerToken, """
                {"debtorAccountId":"%s","billerReferenceNumber":"%s","invoiceReference":"INV-001",
                 "executionDate":"%s","amount":{"value":%s,"currency":"CAD"},"note":"hydro bill"}
                """.formatted(accountId, billerReference, LocalDate.now(), BILL_AMOUNT),
                "Idempotency-Key", IDEMPOTENCY_KEY).require(202);

        paymentId = UUID.fromString(accepted.get("paymentId").asText());
        assertThat(accepted.get("state").asText()).isEqualTo("FUNDS_HELD");

        // The money is reserved but not yet taken.
        assertThat(balance()).isEqualByComparingTo(OPENING_BALANCE);
        assertThat(availableBalance()).isEqualByComparingTo(OPENING_BALANCE.subtract(BILL_AMOUNT));

        // --- the platform batches and submits it on its own -----------------
        // Outbox publishing, batching and settlement upload all happen
        // asynchronously, so the test waits for the outcome rather than for
        // any particular intermediate step.
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(paymentState()).isEqualTo("SUBMITTED"));

        UUID batchId = batchId();
        assertThat(batchId).as("the worker must have assigned a batch").isNotNull();

        // --- the clearing system confirms the payment -----------------------
        // The mock clearing system accepts the trigger and emits the file
        // asynchronously, so it answers 202.
        Rest.post(worker() + "/pain002/" + batchId, customerToken, "").require(202);

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(paymentState()).isEqualTo("POSTED"));

        // --- the customer's money moved exactly once ------------------------
        BigDecimal expected = OPENING_BALANCE.subtract(BILL_AMOUNT);
        assertThat(balance()).isEqualByComparingTo(expected);
        assertThat(availableBalance())
                .as("nothing is held any more, so available equals the balance")
                .isEqualByComparingTo(expected);
        assertThat(activeHolds()).isZero();

        // The held funds were taken in one operation rather than released and
        // then debited, so the hold ends CAPTURED and the statement shows the
        // single posting that moved the money.
        assertThat(queryCount("accountsdb",
                "select count(*) from account_hold where account_id = ? and status = 'CAPTURED'",
                accountId))
                .as("the hold must end captured, not released")
                .isEqualTo(1);

        assertThat(queryCount("accountsdb",
                "select count(*) from account_transaction where request_fingerprint = ?",
                paymentId + ":capture"))
                .as("one ledger entry for the capture")
                .isEqualTo(1);

        assertThat(queryCount("accountsdb",
                "select count(*) from account_transaction where account_id = ? and type = 'HOLD_RELEASED'",
                accountId))
                .as("a captured payment records no release")
                .isZero();
    }

    @Test
    @Order(2)
    @DisplayName("replaying the same payment request creates no second payment, hold or event")
    void replayIsIdempotent() {
        BigDecimal balanceBeforeReplay = balance();

        JsonNode replay = Rest.post(payments() + "/payments/billpay", customerToken, """
                {"debtorAccountId":"%s","billerReferenceNumber":"%s","invoiceReference":"INV-001",
                 "executionDate":"%s","amount":{"value":%s,"currency":"CAD"},"note":"hydro bill"}
                """.formatted(accountId, billerReference, LocalDate.now(), BILL_AMOUNT),
                "Idempotency-Key", IDEMPOTENCY_KEY).require(202);

        assertThat(UUID.fromString(replay.get("paymentId").asText()))
                .as("the replay must return the original payment")
                .isEqualTo(paymentId);

        assertThat(countPayments()).as("no second payment row").isEqualTo(1);
        assertThat(countOutboxEvents()).as("no second billpay.requested event").isEqualTo(1);
        assertThat(countHolds()).as("no second hold on the account").isEqualTo(1);
        assertThat(balance())
                .as("a replay must not move money")
                .isEqualByComparingTo(balanceBeforeReplay);
    }

    @Test
    @Order(3)
    @DisplayName("a debit repeated under the same key takes the money once")
    void debitIsIdempotent() {
        // What a redelivered settlement confirmation looks like to Account
        // Service: the same posting, asked for twice. The orchestrator's own
        // guard cannot help when its first attempt debited and then failed to
        // commit, so this must hold on the Account Service side.
        BigDecimal before = balance();
        String key = "integration-debit-" + UUID.randomUUID();
        String body = """
                {"amount":10.00,"reason":"redelivered settlement"}
                """;

        Rest.post(accounts() + "/accounts/" + accountId + "/debit", customerToken, body,
                "Idempotency-Key", key).require(201);
        Rest.post(accounts() + "/accounts/" + accountId + "/debit", customerToken, body,
                "Idempotency-Key", key).require(201);

        assertThat(balance())
                .as("the second request must take nothing")
                .isEqualByComparingTo(before.subtract(new BigDecimal("10.00")));

        assertThat(queryCount("accountsdb",
                "select count(*) from account_transaction where request_fingerprint = ?", key))
                .as("and must leave a single ledger entry")
                .isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("the database enforces one posting per key, not just the application")
    void uniqueConstraintExists() {
        // The application check is a fast path; this constraint is what makes
        // two concurrent retries safe. It is declared on the entity, but
        // ddl-auto: update does not reliably add a constraint to a table that
        // already exists, so it is worth confirming it is really there.
        int constraints = queryCount("accountsdb", """
                select count(*) from pg_constraint
                where conname = ? and contype = 'u'
                """, "uk_tx_account_idem");

        assertThat(constraints)
                .as("uk_tx_account_idem must exist on account_transaction")
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("another customer cannot read this payment, and is told it does not exist")
    void anotherCustomerCannotReadThePayment() {
        // Same scopes, different customer: the only thing separating them is
        // ownership of the payment.
        String intruderToken = stack.identity().userToken(
                "cust-" + UUID.randomUUID(), IdentityProviderStub.allUserScopes().toArray(String[]::new));

        var response = Rest.get(payments() + "/payments/" + paymentId, intruderToken);

        assertThat(response.status())
                .as("a refusal would confirm the id exists; missing gives nothing away")
                .isEqualTo(404);

        // The owner still reads it, so the endpoint is not simply broken.
        assertThat(Rest.get(payments() + "/payments/" + paymentId, customerToken).status())
                .isEqualTo(200);
    }

    @Test
    @Order(6)
    @DisplayName("initiating a payment without the bill-write scope is refused")
    void billPayRequiresTheBillWriteScope() {
        // A valid token for this same customer, carrying every other scope.
        String weakToken = stack.identity().userToken(customerId,
                "fdx:accounts.read", "fdx:accounts.write", "fdx:bill.read");

        var response = Rest.post(payments() + "/payments/billpay", weakToken, """
                {"debtorAccountId":"%s","billerReferenceNumber":"%s","invoiceReference":"INV-002",
                 "executionDate":"%s","amount":{"value":5.00,"currency":"CAD"},"note":"no scope"}
                """.formatted(accountId, billerReference, LocalDate.now()),
                "Idempotency-Key", "integration-noscope-" + UUID.randomUUID());

        assertThat(response.status())
                .as("the scope is what stops any authenticated token reaching this")
                .isEqualTo(403);
    }

    @Test
    @Order(7)
    @DisplayName("the payment records the customer who asked for it")
    void paymentRecordsItsCustomer() {
        // The column ownership checks read. ddl-auto: update adds it, but that
        // is worth confirming rather than assuming.
        assertThat(queryCount("paymentdb", """
                select count(*) from information_schema.columns
                where table_name = 'payments' and column_name = ?
                """, "customer_id"))
                .as("payments.customer_id must exist")
                .isEqualTo(1);

        assertThat(queryCount("paymentdb",
                "select count(*) from payments where payment_id = ? and customer_id = '" + customerId + "'",
                paymentId))
                .as("and must hold the customer who made this payment")
                .isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("paying from an account the caller does not own is refused, not reported as a server error")
    void payingFromAnotherCustomersAccountIsRefused() {
        // The caller holds every scope, so the scope check passes and the
        // request reaches Account Service, which refuses the hold because the
        // account is not theirs. Before the downstream status was translated,
        // that refusal reached the caller as a 500: the platform reporting its
        // own failure for a request it had correctly declined.
        String intruderToken = stack.identity().userToken(
                "cust-" + UUID.randomUUID(), IdentityProviderStub.allUserScopes().toArray(String[]::new));

        var response = Rest.post(payments() + "/payments/billpay", intruderToken, """
                {"debtorAccountId":"%s","billerReferenceNumber":"%s","invoiceReference":"INV-003",
                 "executionDate":"%s","amount":{"value":5.00,"currency":"CAD"},"note":"not my account"}
                """.formatted(accountId, billerReference, LocalDate.now()),
                "Idempotency-Key", "integration-intruder-" + UUID.randomUUID());

        assertThat(response.status())
                .as("a refusal, not a server error")
                .isEqualTo(403);

        assertThat(response.raw())
                .as("the downstream response body must not be passed on")
                .doesNotContain("com.account", "ensureOwnerOrAdmin", "select ");
    }

    // ----------------------------------------------------------- queries

    private String paymentState() {
        return Rest.get(payments() + "/payments/" + paymentId, customerToken)
                .require(200).get("state").asText();
    }

    @Test
    @Order(9)
    @DisplayName("a customer cannot create money, by credit or by opening balance")
    void aCustomerCannotCreateMoney() {
        // This token carries every scope an end user is issued, including
        // fdx:accounts.write, and owns the account. That used to be enough to
        // credit it: money from nothing, with the ledger recording only that it
        // had appeared. Both ways in are administrative now.
        BigDecimal before = balance();

        var credited = Rest.post(accounts() + "/accounts/" + accountId + "/credit", customerToken, """
                {"amount":1000000.00,"reason":"a million please"}
                """);

        assertThat(credited.status())
                .as("an owner crediting their own account is refused")
                .isEqualTo(403);

        var opened = Rest.post(accounts() + "/accounts", customerToken, """
                {"customerId":"%s","accountType":"CHEQUING","accountSubType":"PERSONAL",
                 "status":"ACTIVE","currency":"CAD","nickname":"Rich",
                 "displayName":"Rich","openingBalance":1000000.00}
                """.formatted(customerId));

        assertThat(opened.status())
                .as("the same fabrication in one call, so the same refusal")
                .isEqualTo(403);

        assertThat(balance())
                .as("nothing appeared")
                .isEqualByComparingTo(before);
    }

    private UUID batchId() {
        JsonNode batch = Rest.get(payments() + "/payments/" + paymentId, customerToken)
                .require(200).get("batchId");
        return batch == null || batch.isNull() ? null : UUID.fromString(batch.asText());
    }

    private BigDecimal balance() {
        return Rest.get(accounts() + "/accounts/" + accountId + "/balance", customerToken)
                .require(200).get("balance").decimalValue();
    }

    private BigDecimal availableBalance() {
        return Rest.get(accounts() + "/accounts/" + accountId + "/balance", customerToken)
                .require(200).get("available").decimalValue();
    }

    private int activeHolds() {
        return queryCount("accountsdb",
                "select count(*) from account_hold where account_id = ? and status = 'ACTIVE'",
                accountId);
    }

    private int countHolds() {
        return queryCount("accountsdb", "select count(*) from account_hold where account_id = ?", accountId);
    }

    private int countPayments() {
        return queryCount("paymentdb", "select count(*) from payments where idempotency_key = ?",
                IDEMPOTENCY_KEY);
    }

    private int countOutboxEvents() {
        return queryCount("paymentdb", "select count(*) from outbox where key = ? and topic = 'billpay.requested'",
                paymentId);
    }

    private int queryCount(String database, String sql, Object parameter) {
        try (Connection connection = DriverManager.getConnection(
                stack.jdbcUrlFor(database), stack.dbUser(), stack.dbPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query failed on " + database + ": " + sql, e);
        }
    }
}
