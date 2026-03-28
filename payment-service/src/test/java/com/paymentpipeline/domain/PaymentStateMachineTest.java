package com.paymentpipeline.domain;

import com.paymentpipeline.shared.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    // -----------------------------------------------------------------------
    // canTransitionTo() — valid transitions
    // -----------------------------------------------------------------------

    @Test
    void validTransitions_shouldSucceed() {
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.SETTLEMENT_PENDING)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.SETTLEMENT_PENDING.canTransitionTo(PaymentStatus.SETTLED)).isTrue();
        assertThat(PaymentStatus.SETTLEMENT_PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
    }

    @Test
    void invalidTransitions_shouldReturnFalse() {
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.SETTLED)).isFalse();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.SETTLEMENT_PENDING)).isFalse();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.INITIATED)).isFalse();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.SETTLED)).isFalse();
        assertThat(PaymentStatus.SETTLEMENT_PENDING.canTransitionTo(PaymentStatus.INITIATED)).isFalse();
        assertThat(PaymentStatus.SETTLEMENT_PENDING.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void terminalState_SETTLED_cannotTransitionToAnything(PaymentStatus next) {
        assertThat(PaymentStatus.SETTLED.canTransitionTo(next)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void terminalState_FAILED_cannotTransitionToAnything(PaymentStatus next) {
        assertThat(PaymentStatus.FAILED.canTransitionTo(next)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Payment.transitionTo() — domain method throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void payment_validTransition_updatesStatus() {
        Payment payment = buildPayment();
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void payment_invalidTransition_throwsIllegalStateException() {
        Payment payment = buildPayment(); // INITIATED
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        payment.transitionTo(PaymentStatus.SETTLEMENT_PENDING);
        payment.transitionTo(PaymentStatus.SETTLED);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.AUTHORIZED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SETTLED")
                .hasMessageContaining("AUTHORIZED");
    }

    @Test
    void payment_fail_setsReasonAndTransitionsToFailed() {
        Payment payment = buildPayment();
        payment.fail("insufficient_funds");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void payment_failFromTerminalState_throwsIllegalStateException() {
        Payment payment = buildPayment();
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        payment.transitionTo(PaymentStatus.SETTLEMENT_PENDING);
        payment.transitionTo(PaymentStatus.SETTLED);

        assertThatThrownBy(() -> payment.fail("late"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Payment buildPayment() {
        return Payment.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .merchantId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }
}
