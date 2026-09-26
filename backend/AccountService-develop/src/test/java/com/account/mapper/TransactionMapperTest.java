package com.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.account.dto.TransactionResponse;
import com.account.model.Transaction;
import com.account.model.TransactionStatus;
import com.account.model.TransactionType;

/**
 * What a ledger row looks like by the time a client sees it.
 *
 * <p>A statement is read by scanning dates down one column and telling money in
 * from money out at a glance. Neither is possible without {@code occurredAt} and
 * {@code type}, and until this test existed both were absent from the response
 * while sitting on the entity the whole time — a gap nothing failed on, because
 * nothing asserted the mapping's output field by field.</p>
 *
 * <p>The mapper is generated, so these assertions are cheap to lose: adding an
 * explicit @Mapping for one field, or renaming one on either side, silently
 * drops it. That is what this test is for.</p>
 */
class TransactionMapperTest {

    private final TransactionMapper mapper = new TransactionMapperImpl();

    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final OffsetDateTime OCCURRED =
            OffsetDateTime.of(2026, 9, 24, 14, 22, 0, 0, ZoneOffset.UTC);

    private static Transaction ledgerRow(TransactionType type) {
        Transaction tx = new Transaction();
        tx.setTransactionId(TRANSACTION_ID);
        tx.setAccountId(UUID.randomUUID());
        tx.setType(type);
        tx.setStatus(TransactionStatus.POSTED);
        tx.setAmount(new BigDecimal("142.50"));
        tx.setCurrency("CAD");
        tx.setReason("City Hydro — invoice INV-2026-118");
        tx.setBalanceAfter(new BigDecimal("4218.63"));
        tx.setOccurredAt(OCCURRED);
        return tx;
    }

    @Test
    @DisplayName("every field a statement is drawn from survives the mapping")
    void mapsEveryFieldAStatementNeeds() {
        TransactionResponse response = mapper.toResponse(ledgerRow(TransactionType.DEBIT));

        assertThat(response.id()).isEqualTo(TRANSACTION_ID);
        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.amount()).isEqualByComparingTo("142.50");
        assertThat(response.reason()).isEqualTo("City Hydro — invoice INV-2026-118");
        assertThat(response.balanceAfter()).isEqualByComparingTo("4218.63");

        assertThat(response.type())
                .as("without this a client cannot tell money arriving from money leaving")
                .isEqualTo("DEBIT");

        assertThat(response.occurredAt())
                .as("without this a client cannot date or order a row")
                .isEqualTo(OCCURRED);
    }

    static Stream<Arguments> everyKindOfMovement() {
        return Stream.of(
                Arguments.of(TransactionType.CREDIT, "CREDIT"),
                Arguments.of(TransactionType.DEBIT, "DEBIT"),
                Arguments.of(TransactionType.HOLD_PLACED, "HOLD_PLACED"),
                Arguments.of(TransactionType.HOLD_RELEASED, "HOLD_RELEASED"));
    }

    @ParameterizedTest(name = "{0} is reported as {1}")
    @MethodSource("everyKindOfMovement")
    @DisplayName("the ledger's own word for the movement is what is sent")
    void sendsTheLedgersOwnWord(TransactionType type, String expected) {
        // Not translated here. What a customer should be shown - "Deposit",
        // "Funds held" - is a presentation decision, and an API that decides it
        // leaves every other client stuck with this one's vocabulary.
        assertThat(mapper.toResponse(ledgerRow(type)).type()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an instant is carried as an instant, not flattened to a local time")
    void keepsTheOffset() {
        // The ledger records when something happened, which is a moment in time,
        // not a wall clock reading: dropping the offset would move every row by
        // the server's distance from UTC.
        TransactionResponse response = mapper.toResponse(ledgerRow(TransactionType.CREDIT));

        assertThat(response.occurredAt().toInstant()).isEqualTo(OCCURRED.toInstant());
        assertThat(response.occurredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }
}
