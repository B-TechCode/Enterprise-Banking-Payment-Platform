package com.payments.orch.domain;

/**
 * Lifecycle of a bill payment, in the order a payment moves through it.
 *
 * <p>The declaration order is the lifecycle order and {@link #canMoveTo} relies
 * on it: reorder these constants and the transition rule changes with them.
 * PaymentStateTest pins every transition so such a change cannot go unnoticed.</p>
 */
public enum PaymentState {
  FUNDS_HELD, BATCHED, SUBMITTED, POSTED, FAILED;

  /**
   * POSTED and FAILED end a payment: its funds have either been debited or
   * released. Nothing may change a finished payment.
   */
  public boolean isFinal() {
    return this == POSTED || this == FAILED;
  }

  /**
   * Whether an event may move a payment from this state to {@code next}.
   *
   * <p>Payments only move forward, and never out of a final state. Events arrive
   * at least once and not necessarily in order, so without this rule a
   * duplicate confirmation could debit a payment that is already POSTED, a late
   * FAILED could mark a debited payment as failed, and a late batch event could
   * move a finished payment back to an earlier state, reopening it.</p>
   */
  public boolean canMoveTo(PaymentState next) {
    return !isFinal() && next.ordinal() > this.ordinal();
  }
}
