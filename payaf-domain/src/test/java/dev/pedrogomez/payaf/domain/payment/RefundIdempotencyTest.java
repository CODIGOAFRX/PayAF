package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundIdempotencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final PaymentId ID = PaymentId.of(new UUID(0, 1));
    private static final PaymentRequest REQUEST = new PaymentRequest(Money.of(1_000, Currency.EUR),
            CountryCode.of("ES"), "pedido-1", IdempotencyKey.of("k1"));

    private static Payment captured() {
        Payment p = Payment.create(ID, REQUEST, T0);
        p.authorize(GatewayId.of("stripe"), "ch_1", T0.plusSeconds(1));
        p.capture(T0.plusSeconds(2));
        p.pullEvents();
        return p;
    }

    private static Money eur(long minor) {
        return Money.of(minor, Currency.EUR);
    }

    @Test
    @DisplayName("el mismo reembolso dos veces se aplica una sola vez y no emite eventos la segunda")
    void sameReferenceIsIdempotent() {
        Payment p = captured();

        assertThat(p.refund(eur(300), "re_1", T0.plusSeconds(3))).isEqualTo(RefundOutcome.APPLIED);
        assertThat(p.pullEvents()).singleElement().isInstanceOfSatisfying(PaymentRefunded.class,
                e -> assertThat(e.gatewayRefundReference()).isEqualTo("re_1"));

        assertThat(p.refund(eur(300), "re_1", T0)).isEqualTo(RefundOutcome.ALREADY_APPLIED);
        assertThat(p.pullEvents()).isEmpty();
        assertThat(p.refundedAmount()).isEqualTo(eur(300));
        assertThat(p.refunds()).extracting(Refund::gatewayRefundReference).containsExactly("re_1");
    }

    @Test
    @DisplayName("repetir el último reembolso de un pago REFUNDED no es un error")
    void repeatOnFullyRefunded() {
        Payment p = captured();
        p.refund(eur(1_000), "re_all", T0.plusSeconds(3));

        assertThat(p.refund(eur(1_000), "re_all", T0.plusSeconds(4))).isEqualTo(RefundOutcome.ALREADY_APPLIED);
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("la misma referencia con otro importe se rechaza, y la referencia no puede estar en blanco")
    void conflictingOrBlankReference() {
        Payment p = captured();
        p.refund(eur(300), "re_1", T0.plusSeconds(3));

        assertThatThrownBy(() -> p.refund(eur(400), "re_1", T0.plusSeconds(4)))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> p.refund(eur(1), "  ", T0.plusSeconds(4)))
                .isInstanceOf(InvalidValueException.class);
        assertThat(p.refundedAmount()).isEqualTo(eur(300));
    }

    @Test
    @DisplayName("refunds() no se puede modificar desde fuera")
    void refundsAreUnmodifiable() {
        Payment p = captured();
        p.refund(eur(300), "re_1", T0.plusSeconds(3));

        assertThatThrownBy(() -> p.refunds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rehydrate exige que los reembolsos sumen lo reembolsado y no repitan referencia")
    void rehydrateValidatesRefunds() {
        List<Refund> duplicated = List.of(new Refund("re_1", eur(100), T0), new Refund("re_1", eur(200), T0));
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, PaymentStatus.PARTIALLY_REFUNDED, List.of(),
                GatewayId.of("stripe"), "ch_1", null, eur(300), duplicated, T0, T0.plusSeconds(1), 0L))
                .isInstanceOf(InconsistentPaymentStateException.class);

        List<Refund> short_ = List.of(new Refund("re_1", eur(100), T0));
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, PaymentStatus.PARTIALLY_REFUNDED, List.of(),
                GatewayId.of("stripe"), "ch_1", null, eur(300), short_, T0, T0.plusSeconds(1), 0L))
                .isInstanceOf(InconsistentPaymentStateException.class);

        List<Refund> ok = List.of(new Refund("re_1", eur(100), T0), new Refund("re_2", eur(200), T0));
        Payment p = Payment.rehydrate(ID, REQUEST, PaymentStatus.PARTIALLY_REFUNDED, List.of(),
                GatewayId.of("stripe"), "ch_1", null, eur(300), ok, T0, T0.plusSeconds(1), 0L);
        assertThat(p.refund(eur(200), "re_2", T0.plusSeconds(2))).isEqualTo(RefundOutcome.ALREADY_APPLIED);
    }
}
