package com.account.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;


/**
 * A request to reserve funds on an account.
 *
 * <p>Carries the currency the amount is stated in so that the service holding
 * the account can refuse a mismatch. Without it a payment in one currency could
 * be held, and then debited, against an account in another at an implied rate
 * of 1:1 - nothing below the payment record carried a currency at all.</p>
 */
public record CreateHoldRequest(
        @NotNull @Positive BigDecimal amount,

        /** ISO-4217, and must match the currency the account is held in. */
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$",
                message = "currency must follow ISO-4217 format (e.g., CAD, USD)")
        String currency,

        String reason,
        LocalDateTime releaseAt,
        String idempotencyKey
) {
    public CreateHoldRequest withIdempotencyKey(String k) {
        return new CreateHoldRequest(this.amount(), this.currency(), this.reason(), this.releaseAt(), k);
    }
}