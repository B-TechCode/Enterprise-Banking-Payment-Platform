package com.account.dto;


import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A ledger row, as a customer's client reads it.
 *
 * <p>{@code type} and {@code occurredAt} are what make a list of these a
 * statement rather than a list of numbers: without the type there is no way to
 * tell money arriving from money leaving, and without the time there is no way
 * to order or date a row. Both have always been on the entity; they were simply
 * never exposed, so every consumer had to guess or do without.</p>
 *
 * <p>{@code type} is the ledger's own word - CREDIT, DEBIT, HOLD_PLACED,
 * HOLD_RELEASED - and is deliberately not translated here. What a customer
 * should be shown is a presentation decision, and the API has no business
 * making it.</p>
 */
public record TransactionResponse(
        UUID id,
        String status,
        String type,
        BigDecimal amount,
        String reason,
        BigDecimal balanceAfter,
        OffsetDateTime occurredAt
) {}
