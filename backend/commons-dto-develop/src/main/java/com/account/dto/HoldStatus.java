package com.account.dto;

/**
 * Lifecycle of a hold on an account.
 *
 * <p>A hold reserves funds without moving them. It ends in one of two ways: the
 * reservation is given up ({@code RELEASED}, {@code CANCELED}, {@code EXPIRED}),
 * or the reserved funds are taken ({@code CAPTURED}).</p>
 */
public enum HoldStatus {

    ACTIVE,

    RELEASED,

    CANCELED,

    EXPIRED,

    /**
     * The held funds were debited as one operation, without being released
     * first. Releasing and then debiting leaves a window in which the funds are
     * unreserved and can be spent, after which the debit fails and the payment
     * can no longer be collected.
     */
    CAPTURED
}
