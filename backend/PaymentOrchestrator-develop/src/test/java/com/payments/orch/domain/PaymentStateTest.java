package com.payments.orch.domain;

import static com.payments.orch.domain.PaymentState.BATCHED;
import static com.payments.orch.domain.PaymentState.FAILED;
import static com.payments.orch.domain.PaymentState.FUNDS_HELD;
import static com.payments.orch.domain.PaymentState.POSTED;
import static com.payments.orch.domain.PaymentState.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins every transition of the payment lifecycle.
 *
 * <p>The allowed transitions are written out here rather than derived, so the
 * test does not simply restate the rule it checks. canMoveTo compares
 * declaration order, so reordering the enum constants would silently change
 * which transitions are allowed; this table would then fail.</p>
 */
class PaymentStateTest {

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.of(
            FUNDS_HELD, EnumSet.of(BATCHED, SUBMITTED, POSTED, FAILED),
            BATCHED, EnumSet.of(SUBMITTED, POSTED, FAILED),
            SUBMITTED, EnumSet.of(POSTED, FAILED),
            POSTED, EnumSet.noneOf(PaymentState.class),
            FAILED, EnumSet.noneOf(PaymentState.class));

    @Test
    @DisplayName("exactly the forward transitions are allowed, for all 25 pairs")
    void transitionTable() {
        for (PaymentState from : PaymentState.values()) {
            for (PaymentState to : PaymentState.values()) {
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("only POSTED and FAILED are final")
    void finalStates() {
        assertThat(EnumSet.allOf(PaymentState.class))
                .filteredOn(PaymentState::isFinal)
                .containsExactlyInAnyOrder(POSTED, FAILED);
    }
}
