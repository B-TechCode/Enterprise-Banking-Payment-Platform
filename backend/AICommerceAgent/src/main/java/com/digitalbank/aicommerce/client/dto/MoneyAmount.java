package com.digitalbank.aicommerce.client.dto;

import java.math.BigDecimal;

/**
 * Amount in the shape the payment orchestrator expects: value first, then an
 * ISO-4217 currency code.
 */
public record MoneyAmount(
        BigDecimal value,
        String currency) {
}
