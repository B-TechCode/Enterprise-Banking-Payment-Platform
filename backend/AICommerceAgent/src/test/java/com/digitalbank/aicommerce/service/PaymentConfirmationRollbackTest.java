package com.digitalbank.aicommerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.InsufficientFundsException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

/**
 * A refusal must not roll the proposal back to PENDING_CONFIRMATION.
 *
 * <p>confirm() moves the proposal to CONFIRMED before it calls the orchestrator,
 * and that marker is what makes a retry replay against the same
 * Idempotency-Key instead of staging and charging a second payment. Spring rolls
 * back on any unchecked exception unless told otherwise, so every exception the
 * method deliberately throws after that point has to appear in
 * {@code noRollbackFor}.</p>
 *
 * <p>This cannot be caught by the behavioural tests: with a mocked repository
 * there is no transaction to roll back, so {@code PaymentConfirmationRefusalTest}
 * passes whether or not the annotation lists these types. The damage would only
 * show against a real database, as a proposal quietly returned to pending and a
 * customer charged twice for retrying. Hence a structural assertion on the
 * annotation itself.</p>
 *
 * <p>Adding a new refusal to the catch blocks in confirm() without adding it
 * here is exactly the mistake this test exists to stop, so it is written to fail
 * on an incomplete list rather than merely to describe the current one.</p>
 */
class PaymentConfirmationRollbackTest {

    @Test
    @DisplayName("every exception confirm() throws after CONFIRMED is exempt from rollback")
    void confirmDoesNotRollBackOnRefusals() throws Exception {

        Method confirm = PaymentConfirmationService.class.getMethod("confirm", UUID.class);
        Transactional transactional = confirm.getAnnotation(Transactional.class);

        assertThat(transactional)
                .as("confirm() must be transactional for the rollback rules to mean anything")
                .isNotNull();

        assertThat(transactional.noRollbackFor())
                .as("a rollback here returns the proposal to PENDING_CONFIRMATION and "
                        + "undoes the protection against a double charge on retry")
                .contains(
                        UpstreamException.class,
                        ConflictException.class,
                        ResourceNotFoundException.class,
                        ForbiddenException.class,
                        InsufficientFundsException.class);
    }
}
