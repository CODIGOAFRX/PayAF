package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentEvent;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.CurrencyMismatchException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Eventos, intentos, rehidratación y casos feos del agregado {@link Payment}. */
class PaymentAggregateEdgeTest {

    private static final Instant T0 = Instant.parse("2026-02-01T08:00:00Z");
    private static final PaymentId ID = PaymentId.of(UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
    private static final GatewayId STRIPE = GatewayId.of("stripe");
    private static final GatewayId ADYEN = GatewayId.of("adyen");
    private static final PaymentRequest REQUEST = new PaymentRequest(Money.of(1_000, Currency.EUR),
            CountryCode.of("ES"), "pedido-e", IdempotencyKey.of("k-e"));

    private static GatewayAttempt attempt(GatewayId gw, AttemptOutcome outcome, Instant at) {
        return new GatewayAttempt(gw, outcome, Duration.ofMillis(80), Optional.empty(), Optional.empty(), at);
    }

    // ---------------------------------------------------------------------------------
    // pullEvents
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("pullEvents devuelve todo el ciclo de vida en orden, con los datos de cada hecho, y vacía la lista")
    void pullEventsInOrder() {
        Payment p = Payment.create(ID, REQUEST, T0);
        GatewayAttempt first = attempt(ADYEN, AttemptOutcome.TIMEOUT, T0.plusSeconds(1));
        GatewayAttempt second = attempt(STRIPE, AttemptOutcome.APPROVED, T0.plusSeconds(2));
        p.recordAttempt(first);
        p.recordAttempt(second);
        p.authorize(STRIPE, "ch_9", T0.plusSeconds(3));
        p.capture(T0.plusSeconds(4));
        p.refund(Money.of(250, Currency.EUR), TestRefs.next(), T0.plusSeconds(5));
        p.refund(Money.of(750, Currency.EUR), TestRefs.next(), T0.plusSeconds(6));

        List<DomainEvent> events = p.pullEvents();
        assertThat(events).hasExactlyElementsOfTypes(PaymentCreated.class, GatewayAttemptRecorded.class,
                GatewayAttemptRecorded.class, PaymentAuthorized.class, PaymentCaptured.class,
                PaymentRefunded.class, PaymentRefunded.class);
        assertThat(events).extracting(DomainEvent::occurredAt).isSorted()
                .containsExactly(T0, T0.plusSeconds(1), T0.plusSeconds(2), T0.plusSeconds(3),
                        T0.plusSeconds(4), T0.plusSeconds(5), T0.plusSeconds(6));
        assertThat(events).allSatisfy(e -> assertThat(((PaymentEvent) e).paymentId()).isEqualTo(ID));
        assertThat(((GatewayAttemptRecorded) events.get(1)).attempt()).isEqualTo(first);
        assertThat(((GatewayAttemptRecorded) events.get(2)).attempt()).isEqualTo(second);
        PaymentAuthorized authorized = (PaymentAuthorized) events.get(3);
        assertThat(authorized.gateway()).isEqualTo(STRIPE);
        assertThat(authorized.gatewayReference()).isEqualTo("ch_9");
        assertThat(((PaymentCaptured) events.get(4)).amount()).isEqualTo(Money.of(1_000, Currency.EUR));
        PaymentRefunded r1 = (PaymentRefunded) events.get(5);
        PaymentRefunded r2 = (PaymentRefunded) events.get(6);
        assertThat(r1.amount()).isEqualTo(Money.of(250, Currency.EUR));
        assertThat(r1.totalRefunded()).isEqualTo(Money.of(250, Currency.EUR));
        assertThat(r2.amount()).isEqualTo(Money.of(750, Currency.EUR));
        assertThat(r2.totalRefunded()).isEqualTo(Money.of(1_000, Currency.EUR));
        PaymentCreated created = (PaymentCreated) events.getFirst();
        assertThat(created.amount()).isEqualTo(REQUEST.amount());
        assertThat(created.country()).isEqualTo(REQUEST.country());
        assertThat(created.merchantReference()).isEqualTo("pedido-e");

        assertThat(p.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("la lista devuelta por pullEvents es una copia: tocarla no afecta al agregado")
    void pulledEventsAreDetached() {
        Payment p = Payment.create(ID, REQUEST, T0);
        List<DomainEvent> pulled = p.pullEvents();
        assertThatThrownBy(() -> pulled.add(pulled.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(pulled::clear).isInstanceOf(UnsupportedOperationException.class);

        p.authorize(STRIPE, "ch_1", T0.plusSeconds(1));
        assertThat(pulled).as("los eventos nuevos no aparecen en una extracción anterior").hasSize(1);
        assertThat(p.pullEvents()).singleElement().isInstanceOf(PaymentAuthorized.class);
    }

    @Test
    @DisplayName("tras pullEvents, los eventos posteriores se acumulan desde cero")
    void eventsAccumulateAfterPull() {
        Payment p = Payment.create(ID, REQUEST, T0);
        p.pullEvents();
        p.authorize(STRIPE, "ch_1", T0.plusSeconds(1));
        p.capture(T0.plusSeconds(2));
        assertThat(p.pullEvents()).hasExactlyElementsOfTypes(PaymentAuthorized.class, PaymentCaptured.class);
        assertThat(p.pullEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // attempts()
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("attempts() no se puede modificar por ninguna vía y conserva el orden")
    void attemptsAreUnmodifiable() {
        Payment p = Payment.create(ID, REQUEST, T0);
        GatewayAttempt a1 = attempt(ADYEN, AttemptOutcome.ERROR, T0.plusSeconds(1));
        GatewayAttempt a2 = attempt(STRIPE, AttemptOutcome.DECLINED, T0.plusSeconds(2));
        p.recordAttempt(a1);
        p.recordAttempt(a2);
        List<GatewayAttempt> view = p.attempts();

        assertThat(view).containsExactly(a1, a2);
        GatewayAttempt intruder = attempt(STRIPE, AttemptOutcome.APPROVED, T0);
        assertThatThrownBy(() -> view.add(intruder)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.add(0, intruder)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.set(0, intruder)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(view::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.removeIf(x -> true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.replaceAll(x -> intruder)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.sort(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.iterator().remove()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.listIterator().add(intruder)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.subList(0, 1).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(p.attempts()).containsExactly(a1, a2);
    }

    @Test
    @DisplayName("rehydrate copia la lista de intentos: modificar la original no toca el pago")
    void rehydrateCopiesAttempts() {
        List<GatewayAttempt> source = new ArrayList<>();
        source.add(attempt(ADYEN, AttemptOutcome.ERROR, T0));
        Payment p = Payment.rehydrate(ID, REQUEST, PaymentStatus.PENDING, source, null, null, null,
                Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T0, 0L);
        source.add(attempt(STRIPE, AttemptOutcome.APPROVED, T0));
        source.clear();
        assertThat(p.attempts()).hasSize(1);
        assertThatThrownBy(() -> p.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------------------------
    // rehydrate
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "rehydrate acepta {0}")
    @MethodSource("dev.pedrogomez.payaf.domain.payment.PaymentTransitionMatrixTest#statuses")
    @DisplayName("rehydrate reconstruye cada estado coherente sin emitir eventos")
    void rehydrateEveryCoherentState(PaymentStatus status) {
        Payment p = PaymentTransitionMatrixTest.rehydratedIn(status);
        assertThat(p.status()).isEqualTo(status);
        assertThat(p.pullEvents()).isEmpty();
    }

    static Stream<Arguments> incoherentStates() {
        Money zero = Money.zero(Currency.EUR);
        Money part = Money.of(400, Currency.EUR);
        Money full = Money.of(1_000, Currency.EUR);
        return Stream.of(
                Arguments.of("REFUNDED con reembolso parcial", PaymentStatus.REFUNDED, STRIPE, "r", null, part),
                Arguments.of("REFUNDED sin reembolso", PaymentStatus.REFUNDED, STRIPE, "r", null, zero),
                Arguments.of("PARTIALLY_REFUNDED sin reembolso", PaymentStatus.PARTIALLY_REFUNDED, STRIPE, "r", null, zero),
                Arguments.of("PARTIALLY_REFUNDED con todo reembolsado", PaymentStatus.PARTIALLY_REFUNDED, STRIPE, "r", null, full),
                Arguments.of("CAPTURED con reembolso", PaymentStatus.CAPTURED, STRIPE, "r", null, part),
                Arguments.of("AUTHORIZED con reembolso", PaymentStatus.AUTHORIZED, STRIPE, "r", null, part),
                Arguments.of("PENDING con reembolso", PaymentStatus.PENDING, null, null, null, part),
                Arguments.of("reembolso negativo", PaymentStatus.CAPTURED, STRIPE, "r", null, Money.of(-1, Currency.EUR)),
                Arguments.of("reembolso mayor que el importe", PaymentStatus.REFUNDED, STRIPE, "r", null, Money.of(1_001, Currency.EUR)),
                Arguments.of("reembolso en otra divisa", PaymentStatus.CAPTURED, STRIPE, "r", null, Money.zero(Currency.USD)),
                Arguments.of("AUTHORIZED sin pasarela", PaymentStatus.AUTHORIZED, null, "r", null, zero),
                Arguments.of("AUTHORIZED sin referencia", PaymentStatus.AUTHORIZED, STRIPE, null, null, zero),
                Arguments.of("CAPTURED sin pasarela", PaymentStatus.CAPTURED, null, null, null, zero),
                Arguments.of("CANCELED sin pasarela", PaymentStatus.CANCELED, null, null, null, zero),
                Arguments.of("PENDING con pasarela", PaymentStatus.PENDING, STRIPE, "r", null, zero),
                Arguments.of("PENDING con referencia", PaymentStatus.PENDING, null, "r", null, zero),
                Arguments.of("FAILED con pasarela", PaymentStatus.FAILED, STRIPE, "r", "x", zero),
                Arguments.of("FAILED sin motivo", PaymentStatus.FAILED, null, null, null, zero),
                Arguments.of("PENDING con motivo de fallo", PaymentStatus.PENDING, null, null, "x", zero),
                Arguments.of("CAPTURED con motivo de fallo", PaymentStatus.CAPTURED, STRIPE, "r", "x", zero),
                Arguments.of("FAILED con motivo en blanco", PaymentStatus.FAILED, null, null, "  ", zero),
                Arguments.of("FAILED con motivo vacío", PaymentStatus.FAILED, null, null, "", zero),
                Arguments.of("AUTHORIZED con referencia vacía", PaymentStatus.AUTHORIZED, STRIPE, "", null, zero),
                Arguments.of("CAPTURED con referencia en blanco", PaymentStatus.CAPTURED, STRIPE, " ", null, zero));
    }

    @ParameterizedTest(name = "rehydrate rechaza: {0}")
    @MethodSource("incoherentStates")
    @DisplayName("rehydrate rechaza cualquier combinación que ningún método de negocio puede producir")
    void rehydrateRejectsIncoherent(String description, PaymentStatus status, GatewayId authorizedBy,
                                    String reference, String failure, Money refunded) {
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, status, List.of(), authorizedBy, reference, failure,
                refunded, TestRefs.refundsFor(refunded), T0, T0.plusSeconds(1), 0L))
                .isInstanceOfSatisfying(InconsistentPaymentStateException.class,
                        e -> assertThat(e.code()).isEqualTo("INCONSISTENT_STATE"));
    }

    @Test
    @DisplayName("rehydrate rechaza intentos nulos y fechas invertidas, y acepta createdAt == updatedAt")
    void rehydrateRejectsNullAttemptsAndInvertedDates() {
        List<GatewayAttempt> withNull = Arrays.asList(attempt(STRIPE, AttemptOutcome.ERROR, T0), null);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, PaymentStatus.PENDING, withNull, null, null, null,
                Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T0, 0L)).isInstanceOf(InconsistentPaymentStateException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, PaymentStatus.PENDING, List.of(), null, null, null,
                Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T0.minusNanos(1), 0L)).isInstanceOf(InconsistentPaymentStateException.class);
        assertThatCode(() -> Payment.rehydrate(ID, REQUEST, PaymentStatus.PENDING, List.of(), null, null, null,
                Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T0, 0L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rehydrate rechaza nulos en los campos obligatorios")
    void rehydrateNulls() {
        Money zero = Money.zero(Currency.EUR);
        PaymentStatus pending = PaymentStatus.PENDING;
        assertThatThrownBy(() -> Payment.rehydrate(null, REQUEST, pending, List.of(), null, null, null, zero, TestRefs.refundsFor(zero), T0, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, null, pending, List.of(), null, null, null, zero, TestRefs.refundsFor(zero), T0, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, null, List.of(), null, null, null, zero, TestRefs.refundsFor(zero), T0, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, pending, null, null, null, null, zero, TestRefs.refundsFor(zero), T0, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, pending, List.of(), null, null, null, null, TestRefs.refundsFor(null), T0, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, pending, List.of(), null, null, null, zero, TestRefs.refundsFor(zero), null, T0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, REQUEST, pending, List.of(), null, null, null, zero, TestRefs.refundsFor(zero), T0, null, 0L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("un pago rehidratado sigue su ciclo de vida y emite solo los eventos nuevos")
    void rehydratedPaymentContinues() {
        Payment p = Payment.rehydrate(ID, REQUEST, PaymentStatus.PARTIALLY_REFUNDED, List.of(), STRIPE, "ch_1",
                null, Money.of(999, Currency.EUR), TestRefs.refundsFor(Money.of(999, Currency.EUR)), T0, T0.plusSeconds(1), 0L);
        assertThatThrownBy(() -> p.refund(Money.of(2, Currency.EUR), TestRefs.next(), T0.plusSeconds(2)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        p.refund(Money.of(1, Currency.EUR), TestRefs.next(), T0.plusSeconds(2));
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.pullEvents()).singleElement().isInstanceOfSatisfying(PaymentRefunded.class,
                e -> assertThat(e.totalRefunded()).isEqualTo(Money.of(1_000, Currency.EUR)));
    }

    // ---------------------------------------------------------------------------------
    // Casos feos en los métodos de negocio
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("nulos y textos en blanco en los métodos de negocio se rechazan sin cambiar el estado")
    void businessMethodNullsAndBlanks() {
        Payment p = Payment.create(ID, REQUEST, T0);
        Instant t = T0.plusSeconds(1);
        assertThatThrownBy(() -> p.authorize(null, "r", t)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.authorize(STRIPE, null, t)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.authorize(STRIPE, "r", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.authorize(STRIPE, "", t)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> p.authorize(STRIPE, " \t ", t)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> p.fail(null, t)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.fail("", t)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> p.fail("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.recordAttempt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.capture(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.cancel(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.refund(null, TestRefs.next(), t)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.refund(Money.of(1, Currency.EUR), TestRefs.next(), null)).isInstanceOf(NullPointerException.class);
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.updatedAt()).isEqualTo(T0);
        assertThatThrownBy(() -> Payment.create(null, REQUEST, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.create(ID, null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.create(ID, REQUEST, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("orden de comprobación del reembolso: divisa y signo antes que el estado, como documenta el Javadoc")
    void refundCheckOrder() {
        Payment pending = Payment.create(ID, REQUEST, T0);
        assertThatThrownBy(() -> pending.refund(Money.of(1, Currency.USD), TestRefs.next(), T0))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> pending.refund(Money.of(-1, Currency.EUR), TestRefs.next(), T0))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> pending.refund(Money.of(5_000, Currency.EUR), TestRefs.next(), T0))
                .as("en PENDING lo que falla es el estado, no el importe")
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("GatewayAttempt: latencia negativa y nulos se rechazan; latencia cero vale")
    void gatewayAttemptValidation() {
        assertThatThrownBy(() -> new GatewayAttempt(STRIPE, AttemptOutcome.ERROR, Duration.ofMillis(-1),
                Optional.empty(), Optional.empty(), T0)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayAttempt(STRIPE, AttemptOutcome.ERROR, Duration.ZERO,
                null, Optional.empty(), T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GatewayAttempt(null, AttemptOutcome.ERROR, Duration.ZERO,
                Optional.empty(), Optional.empty(), T0)).isInstanceOf(NullPointerException.class);
        assertThat(new GatewayAttempt(STRIPE, AttemptOutcome.ERROR, Duration.ZERO,
                Optional.of("e"), Optional.of("r"), T0).latency()).isZero();
    }

    @Test
    @DisplayName("AttemptOutcome: solo ERROR y TIMEOUT son fallos técnicos")
    void technicalFailures() {
        assertThat(AttemptOutcome.ERROR.isTechnicalFailure()).isTrue();
        assertThat(AttemptOutcome.TIMEOUT.isTechnicalFailure()).isTrue();
        assertThat(AttemptOutcome.DECLINED.isTechnicalFailure()).isFalse();
        assertThat(AttemptOutcome.APPROVED.isTechnicalFailure()).isFalse();
    }

    @Test
    @DisplayName("igualdad de entidad: mismo id es el mismo pago aunque cambie el estado")
    void entityEquality() {
        Payment a = Payment.create(ID, REQUEST, T0);
        Payment b = Payment.rehydrate(ID, REQUEST, PaymentStatus.FAILED, List.of(), null, null, "x",
                Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T0, 0L);
        Payment c = Payment.create(PaymentId.of(UUID.randomUUID()), REQUEST, T0);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThatThrownBy(() -> PaymentId.of("no-es-un-uuid")).isInstanceOf(IllegalArgumentException.class);
    }
}
