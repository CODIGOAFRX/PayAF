package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.CurrencyMismatchException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tiempo monótono: un método de negocio con un instante anterior a {@code updatedAt} lanza
 * {@link NonMonotonicTimeException}; con un instante igual, funciona. La comprobación va
 * después de validar la transición y el exceso de reembolso.
 */
class PaymentMonotonicTimeTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant LAST = T0.plusSeconds(60);
    private static final GatewayId GW = GatewayId.of("stripe");
    private static final PaymentRequest REQUEST = new PaymentRequest(Money.of(1_000, Currency.EUR),
            CountryCode.of("ES"), "pedido-t", IdempotencyKey.of("k-t"));

    private static GatewayAttempt attemptAt(Instant at) {
        return new GatewayAttempt(GW, AttemptOutcome.ERROR, Duration.ofMillis(10), Optional.empty(),
                Optional.empty(), at);
    }

    /** Pago en {@code status} con {@code updatedAt = LAST}, por métodos de negocio. */
    private static Payment in(PaymentStatus status) {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), REQUEST, T0);
        switch (status) {
            case PENDING -> p.recordAttempt(attemptAt(LAST));
            case AUTHORIZED -> p.authorize(GW, "ref", LAST);
            case CAPTURED -> {
                p.authorize(GW, "ref", T0.plusSeconds(1));
                p.capture(LAST);
            }
            case PARTIALLY_REFUNDED -> {
                p.authorize(GW, "ref", T0.plusSeconds(1));
                p.capture(T0.plusSeconds(2));
                p.refund(Money.of(400, Currency.EUR), TestRefs.next(), LAST);
            }
            default -> throw new IllegalArgumentException(status.name());
        }
        assertThat(p.updatedAt()).isEqualTo(LAST);
        p.pullEvents();
        return p;
    }

    static Stream<Arguments> operations() {
        return Stream.of(
                op("recordAttempt", PaymentStatus.PENDING, (p, t) -> p.recordAttempt(attemptAt(t))),
                op("authorize", PaymentStatus.PENDING, (p, t) -> p.authorize(GW, "ref-2", t)),
                op("fail", PaymentStatus.PENDING, (p, t) -> p.fail("sin pasarelas", t)),
                op("capture", PaymentStatus.AUTHORIZED, (p, t) -> p.capture(t)),
                op("cancel", PaymentStatus.AUTHORIZED, (p, t) -> p.cancel(t)),
                op("refund parcial desde CAPTURED", PaymentStatus.CAPTURED,
                        (p, t) -> p.refund(Money.of(1, Currency.EUR), TestRefs.next(), t)),
                op("refund total desde CAPTURED", PaymentStatus.CAPTURED,
                        (p, t) -> p.refund(Money.of(1_000, Currency.EUR), TestRefs.next(), t)),
                op("refund parcial desde PARTIALLY_REFUNDED", PaymentStatus.PARTIALLY_REFUNDED,
                        (p, t) -> p.refund(Money.of(1, Currency.EUR), TestRefs.next(), t)),
                op("refund del resto desde PARTIALLY_REFUNDED", PaymentStatus.PARTIALLY_REFUNDED,
                        (p, t) -> p.refund(Money.of(600, Currency.EUR), TestRefs.next(), t)));
    }

    private static Arguments op(String name, PaymentStatus from, BiConsumer<Payment, Instant> action) {
        return Arguments.of(name, from, action);
    }

    @ParameterizedTest(name = "{0}: 1 ns antes de updatedAt → NON_MONOTONIC_TIME")
    @MethodSource("operations")
    @DisplayName("cada método de negocio rechaza un instante anterior a updatedAt y no deja rastro")
    void earlierInstantRejected(String name, PaymentStatus from, BiConsumer<Payment, Instant> action) {
        Payment p = in(from);
        PaymentTransitionMatrixTest.Snapshot before = PaymentTransitionMatrixTest.Snapshot.of(p);
        Instant earlier = LAST.minusNanos(1);

        assertThatThrownBy(() -> action.accept(p, earlier))
                .isInstanceOfSatisfying(NonMonotonicTimeException.class, e -> {
                    assertThat(e.code()).isEqualTo("NON_MONOTONIC_TIME");
                    assertThat(e.paymentId()).isEqualTo(p.id());
                    assertThat(e.lastUpdate()).isEqualTo(LAST);
                    assertThat(e.received()).isEqualTo(earlier);
                });
        assertThat(PaymentTransitionMatrixTest.Snapshot.of(p)).isEqualTo(before);
        assertThat(p.pullEvents()).isEmpty();
    }

    @ParameterizedTest(name = "{0}: instante igual a updatedAt vale")
    @MethodSource("operations")
    @DisplayName("un instante igual a updatedAt se acepta, y uno posterior también")
    void sameInstantAccepted(String name, PaymentStatus from, BiConsumer<Payment, Instant> action) {
        Payment same = in(from);
        assertThatCode(() -> action.accept(same, LAST)).doesNotThrowAnyException();
        assertThat(same.updatedAt()).isEqualTo(LAST);
        assertThat(same.pullEvents()).hasSize(1);

        Payment later = in(from);
        action.accept(later, LAST.plusNanos(1));
        assertThat(later.updatedAt()).isEqualTo(LAST.plusNanos(1));
    }

    @Test
    @DisplayName("la referencia es updatedAt, no createdAt: capturar antes de la autorización se rechaza")
    void referenceIsUpdatedAtNotCreatedAt() {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), REQUEST, T0);
        p.authorize(GW, "ref", T0.plusSeconds(10));
        assertThatThrownBy(() -> p.capture(T0.plusSeconds(5))).isInstanceOf(NonMonotonicTimeException.class);
        assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("un instante anterior a la creación se rechaza en el primer cambio")
    void beforeCreationRejected() {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), REQUEST, T0);
        assertThatThrownBy(() -> p.authorize(GW, "ref", T0.minusSeconds(1))).isInstanceOf(NonMonotonicTimeException.class);
        assertThatThrownBy(() -> p.recordAttempt(attemptAt(T0.minusNanos(1))))
                .isInstanceOf(NonMonotonicTimeException.class);
        assertThat(p.attempts()).isEmpty();
        assertThat(p.updatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("un pago rehidratado usa su updatedAt guardado como referencia")
    void rehydratedPaymentUsesStoredUpdatedAt() {
        Payment p = Payment.rehydrate(PaymentId.of(UUID.randomUUID()), REQUEST, PaymentStatus.AUTHORIZED, List.of(),
                GW, "ref", null, Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, LAST, 0L);
        assertThatThrownBy(() -> p.capture(LAST.minusMillis(1))).isInstanceOf(NonMonotonicTimeException.class);
        p.capture(LAST);
        assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    // ---------------------------------------------------------------------------------
    // Orden de las comprobaciones: el tiempo va después de la transición y del exceso
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("con un instante anterior, una transición ilegal sigue siendo ILLEGAL_TRANSITION")
    void illegalTransitionWinsOverTime() {
        Instant earlier = LAST.minusSeconds(30);
        Payment pending = in(PaymentStatus.PENDING);
        assertThatThrownBy(() -> pending.capture(earlier)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> pending.cancel(earlier)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> pending.refund(Money.of(1, Currency.EUR), TestRefs.next(), earlier))
                .isInstanceOf(IllegalPaymentTransitionException.class);

        Payment authorized = in(PaymentStatus.AUTHORIZED);
        assertThatThrownBy(() -> authorized.recordAttempt(attemptAt(earlier)))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> authorized.authorize(GW, "otra", earlier))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> authorized.fail("x", earlier)).isInstanceOf(IllegalPaymentTransitionException.class);

        Payment captured = in(PaymentStatus.CAPTURED);
        assertThatThrownBy(() -> captured.cancel(earlier)).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("con un instante anterior, reembolsar de más sigue siendo REFUND_EXCEEDS_CAPTURED")
    void refundExceedsWinsOverTime() {
        Instant earlier = LAST.minusSeconds(30);
        Payment partial = in(PaymentStatus.PARTIALLY_REFUNDED);
        assertThatThrownBy(() -> partial.refund(Money.of(601, Currency.EUR), TestRefs.next(), earlier))
                .isInstanceOf(RefundExceedsCapturedException.class);
        assertThatThrownBy(() -> partial.refund(Money.of(Long.MAX_VALUE, Currency.EUR), TestRefs.next(), earlier))
                .isInstanceOf(RefundExceedsCapturedException.class);
    }

    @Test
    @DisplayName("con un instante anterior, la validación de los argumentos va primero")
    void argumentValidationWinsOverTime() {
        Instant earlier = LAST.minusSeconds(30);
        Payment pending = in(PaymentStatus.PENDING);
        assertThatThrownBy(() -> pending.authorize(GW, " ", earlier)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> pending.fail("", earlier)).isInstanceOf(InvalidValueException.class);
        Payment captured = in(PaymentStatus.CAPTURED);
        assertThatThrownBy(() -> captured.refund(Money.of(1, Currency.USD), TestRefs.next(), earlier))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> captured.refund(Money.zero(Currency.EUR), TestRefs.next(), earlier))
                .isInstanceOf(InvalidValueException.class);
    }
}
