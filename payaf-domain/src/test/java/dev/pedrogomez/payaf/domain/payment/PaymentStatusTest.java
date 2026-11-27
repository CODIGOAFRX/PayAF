package dev.pedrogomez.payaf.domain.payment;

import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.AUTHORIZED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CANCELED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CAPTURED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.FAILED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PENDING;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    @DisplayName("cada estado declara sus transiciones permitidas")
    void allowedTransitions() {
        assertThat(PENDING.allowedTransitions()).containsExactlyInAnyOrder(AUTHORIZED, FAILED);
        assertThat(AUTHORIZED.allowedTransitions()).containsExactlyInAnyOrder(CAPTURED, CANCELED);
        assertThat(CAPTURED.allowedTransitions()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
        assertThat(PARTIALLY_REFUNDED.allowedTransitions()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
    }

    @Test
    @DisplayName("transiciones no permitidas")
    void forbiddenTransitions() {
        assertThat(PENDING.canTransitionTo(CAPTURED)).isFalse();
        assertThat(AUTHORIZED.canTransitionTo(REFUNDED)).isFalse();
        assertThat(CAPTURED.canTransitionTo(CANCELED)).isFalse();
        assertThat(PARTIALLY_REFUNDED.canTransitionTo(CAPTURED)).isFalse();
        assertThat(PENDING.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("FAILED, CANCELED y REFUNDED son terminales y los demás no")
    void terminalStates() {
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(CANCELED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(PARTIALLY_REFUNDED.isTerminal()).isFalse();
        for (PaymentStatus terminal : new PaymentStatus[] {FAILED, CANCELED, REFUNDED}) {
            for (PaymentStatus target : PaymentStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).isFalse();
            }
        }
    }
}
