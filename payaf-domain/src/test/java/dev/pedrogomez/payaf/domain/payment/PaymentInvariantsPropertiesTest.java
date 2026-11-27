package dev.pedrogomez.payaf.domain.payment;

import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.AUTHORIZED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CANCELED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CAPTURED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.FAILED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PENDING;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * Propiedades del agregado {@link Payment}: por muchas operaciones que se le lancen, y en
 * cualquier orden, nunca queda en un estado ilegal ni se le escapa dinero.
 */
class PaymentInvariantsPropertiesTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> SPEC = new EnumMap<>(Map.of(
            PENDING, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, CANCELED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class)));

    private static final Instant T0 = Instant.parse("2026-05-01T12:00:00Z");
    private static final GatewayId GW = GatewayId.of("stripe");

    // ---------------------------------------------------------------------------------
    // Operaciones aleatorias
    // ---------------------------------------------------------------------------------

    sealed interface Op permits Attempt, Authorize, Fail, Capture, Cancel, Refund {
    }

    record Attempt(AttemptOutcome outcome) implements Op {
    }

    record Authorize(String reference) implements Op {
    }

    record Fail(String reason) implements Op {
    }

    record Capture() implements Op {
    }

    record Cancel() implements Op {
    }

    /** {@code fraction} en milésimas del importe, para cubrir parciales, exactos y de más. */
    record Refund(int permille, long delta, Currency currency) implements Op {
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Op> attempt = Arbitraries.of(AttemptOutcome.class).map(Attempt::new);
        Arbitrary<Op> authorize = Arbitraries.of("ref-1", "", "  ").map(Authorize::new);
        Arbitrary<Op> fail = Arbitraries.of("caídas", "", "\t").map(Fail::new);
        Arbitrary<Op> capture = Arbitraries.just(new Capture());
        Arbitrary<Op> cancel = Arbitraries.just(new Cancel());
        Arbitrary<Op> refund = Combinators.combine(
                        Arbitraries.integers().between(-100, 1_200),
                        Arbitraries.longs().between(-2, 2),
                        Arbitraries.of(Currency.EUR, Currency.EUR, Currency.EUR, Currency.USD))
                .as(Refund::new);
        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(2, attempt),
                        net.jqwik.api.Tuple.of(3, authorize),
                        net.jqwik.api.Tuple.of(1, fail),
                        net.jqwik.api.Tuple.of(3, capture),
                        net.jqwik.api.Tuple.of(1, cancel),
                        net.jqwik.api.Tuple.of(6, refund))
                .list().ofMaxSize(30);
    }

    private static PaymentRequest request(long minor) {
        return new PaymentRequest(Money.of(minor, Currency.EUR), CountryCode.of("ES"), "pedido-p",
                IdempotencyKey.of("k-p"));
    }

    private static void apply(Payment p, Op op, Instant now) {
        switch (op) {
            case Attempt a -> p.recordAttempt(new GatewayAttempt(GW, a.outcome(), Duration.ofMillis(5),
                    Optional.empty(), Optional.empty(), now));
            case Authorize a -> p.authorize(GW, a.reference(), now);
            case Fail f -> p.fail(f.reason(), now);
            case Capture c -> p.capture(now);
            case Cancel c -> p.cancel(now);
            case Refund r -> {
                long base = p.amount().amountMinor() * r.permille() / 1_000 + r.delta();
                p.refund(Money.of(base, r.currency()), now);
            }
        }
    }

    @Property(tries = 2_000)
    @Label("una secuencia aleatoria de operaciones nunca viola los invariantes del pago")
    void randomOperationsKeepInvariants(@ForAll @LongRange(min = 1, max = 100_000) long amount,
                                        @ForAll("operations") List<Op> ops) {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), request(amount), T0);
        long refundedFromEvents = 0;
        List<DomainEvent> allEvents = new ArrayList<>(p.pullEvents());
        Instant now = T0;

        for (Op op : ops) {
            now = now.plusSeconds(1);
            PaymentTransitionMatrixTest.Snapshot before = PaymentTransitionMatrixTest.Snapshot.of(p);
            PaymentStatus from = p.status();
            try {
                apply(p, op, now);
            } catch (DomainException e) {
                assertThat(PaymentTransitionMatrixTest.Snapshot.of(p))
                        .as("%s rechazada con %s no debe cambiar nada", op, e.code()).isEqualTo(before);
                assertThat(p.pullEvents()).as("un rechazo no emite eventos").isEmpty();
                assertInvariants(p);
                continue;
            }
            // Si la operación ha cambiado el estado, el salto está en la especificación.
            if (p.status() != from || op instanceof Refund) {
                assertThat(SPEC.get(from)).as("%s → %s por %s", from, p.status(), op).contains(p.status());
            }
            List<DomainEvent> events = p.pullEvents();
            assertThat(events).as("cada operación aceptada emite exactamente un evento").hasSize(1);
            if (events.getFirst() instanceof PaymentRefunded refunded) {
                refundedFromEvents += refunded.amount().amountMinor();
                assertThat(refunded.totalRefunded()).isEqualTo(p.refundedAmount());
            }
            allEvents.addAll(events);
            assertInvariants(p);
        }

        assertThat(p.refundedAmount().amountMinor())
                .as("lo reembolsado es exactamente la suma de los eventos PaymentRefunded")
                .isEqualTo(refundedFromEvents);
        assertThat(allEvents).allSatisfy(e -> assertThat(e.occurredAt()).isAfterOrEqualTo(T0));

        // Todo estado alcanzable con métodos de negocio se puede guardar y volver a cargar.
        Payment reloaded = Payment.rehydrate(p.id(), p.request(), p.status(), p.attempts(),
                p.authorizedBy().orElse(null), p.gatewayReference().orElse(null), p.failureReason().orElse(null),
                p.refundedAmount(), p.createdAt(), p.updatedAt());
        assertThat(PaymentTransitionMatrixTest.Snapshot.of(reloaded))
                .isEqualTo(PaymentTransitionMatrixTest.Snapshot.of(p));
        assertThat(reloaded.pullEvents()).isEmpty();
    }

    private static void assertInvariants(Payment p) {
        Money amount = p.amount();
        Money refunded = p.refundedAmount();
        assertThat(refunded.currency()).isEqualTo(amount.currency());
        assertThat(refunded.amountMinor()).isBetween(0L, amount.amountMinor());
        switch (p.status()) {
            case PARTIALLY_REFUNDED -> assertThat(refunded.amountMinor()).isStrictlyBetween(0L, amount.amountMinor());
            case REFUNDED -> assertThat(refunded).isEqualTo(amount);
            default -> assertThat(refunded.isZero()).as("sin reembolsos en %s", p.status()).isTrue();
        }
        boolean authorized = p.status() != PENDING && p.status() != FAILED;
        assertThat(p.authorizedBy().isPresent()).isEqualTo(authorized);
        assertThat(p.gatewayReference().isPresent()).isEqualTo(authorized);
        p.gatewayReference().ifPresent(ref -> assertThat(ref).isNotBlank());
        assertThat(p.failureReason().isPresent()).isEqualTo(p.status() == FAILED);
        p.failureReason().ifPresent(reason -> assertThat(reason).isNotBlank());
        assertThat(p.capturedAmount()).isEqualTo(p.status().isCaptured() ? amount : Money.zero(amount.currency()));
        assertThat(p.remainingRefundable().amountMinor()).isBetween(0L, amount.amountMinor());
        assertThat(p.updatedAt()).isAfterOrEqualTo(p.createdAt());
    }

    // ---------------------------------------------------------------------------------
    // Reembolsos parciales
    // ---------------------------------------------------------------------------------

    @Property(tries = 500)
    @Label("reembolsos parciales que suman exactamente el total dejan el pago REFUNDED")
    void partialRefundsSumExactly(@ForAll @LongRange(min = 2, max = 1_000_000) long amount,
                                  @ForAll @Size(min = 1, max = 15) List<@IntRange(min = 1, max = 1_000) Integer> weights) {
        Payment p = captured(amount);
        List<Long> parts = split(amount, weights);
        long sum = 0;
        for (int i = 0; i < parts.size(); i++) {
            p.refund(Money.of(parts.get(i), Currency.EUR), T0.plusSeconds(10 + i));
            sum += parts.get(i);
            assertThat(p.refundedAmount().amountMinor()).isEqualTo(sum);
            assertThat(p.status()).isEqualTo(sum == amount ? REFUNDED : PARTIALLY_REFUNDED);
        }
        assertThat(p.status()).isEqualTo(REFUNDED);
        assertThat(p.remainingRefundable().isZero()).isTrue();
        assertThatThrownBy(() -> p.refund(Money.of(1, Currency.EUR), T0.plusSeconds(100)))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Property(tries = 500)
    @Label("un céntimo de más sobre lo que queda da RefundExceedsCaptured y no cambia nada")
    void oneCentTooMuchIsRejected(@ForAll @LongRange(min = 2, max = 1_000_000) long amount,
                                  @ForAll @LongRange(min = 1, max = 999_999) long alreadyRefunded) {
        if (alreadyRefunded >= amount) {
            return;
        }
        Payment p = captured(amount);
        p.refund(Money.of(alreadyRefunded, Currency.EUR), T0.plusSeconds(10));
        p.pullEvents();
        long remaining = amount - alreadyRefunded;

        assertThatThrownBy(() -> p.refund(Money.of(remaining + 1, Currency.EUR), T0.plusSeconds(11)))
                .isInstanceOfSatisfying(RefundExceedsCapturedException.class, e -> {
                    assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
                    assertThat(e.requested()).isEqualTo(Money.of(remaining + 1, Currency.EUR));
                    assertThat(e.remainingRefundable()).isEqualTo(Money.of(remaining, Currency.EUR));
                    assertThat(e.paymentId()).isEqualTo(p.id());
                });
        assertThat(p.status()).isEqualTo(PARTIALLY_REFUNDED);
        assertThat(p.refundedAmount().amountMinor()).isEqualTo(alreadyRefunded);
        assertThat(p.updatedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(p.pullEvents()).isEmpty();

        p.refund(Money.of(remaining, Currency.EUR), T0.plusSeconds(12));
        assertThat(p.status()).isEqualTo(REFUNDED);
    }

    @Example
    @Label("un reembolso de más en el primer intento, desde CAPTURED, también es RefundExceedsCaptured")
    void overRefundFromCaptured() {
        Payment p = captured(1_000);
        assertThatThrownBy(() -> p.refund(Money.of(1_001, Currency.EUR), T0.plusSeconds(10)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        assertThat(p.status()).isEqualTo(CAPTURED);
    }

    @Example
    @Label("un reembolso enorme (Long.MAX_VALUE) tras uno parcial es RefundExceedsCaptured, no un desbordamiento")
    void hugeRefundAfterPartialIsRefundExceeds() {
        Payment p = captured(1_000);
        p.refund(Money.of(1, Currency.EUR), T0.plusSeconds(10));
        assertThatThrownBy(() -> p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), T0.plusSeconds(11)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        assertThat(p.refundedAmount()).isEqualTo(Money.of(1, Currency.EUR));
    }

    @Example
    @Label("un reembolso enorme sobre un pago capturado sin reembolsos es RefundExceedsCaptured")
    void hugeRefundFromCaptured() {
        Payment p = captured(1_000);
        assertThatThrownBy(() -> p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), T0.plusSeconds(11)))
                .isInstanceOf(RefundExceedsCapturedException.class);
    }

    @Example
    @Label("un pago de Long.MAX_VALUE se puede reembolsar entero en dos partes")
    void hugePaymentRefundsExactly() {
        Payment p = captured(Long.MAX_VALUE);
        p.refund(Money.of(Long.MAX_VALUE - 1, Currency.EUR), T0.plusSeconds(10));
        p.refund(Money.of(1, Currency.EUR), T0.plusSeconds(11));
        assertThat(p.status()).isEqualTo(REFUNDED);
    }

    // ---------------------------------------------------------------------------------
    // El reloj llega de fuera y puede ir hacia atrás
    // ---------------------------------------------------------------------------------

    @Provide
    Arbitrary<List<Integer>> jitters() {
        // Segundos que avanza (o retrocede) el reloj antes de cada operación; muchos ceros.
        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(3, Arbitraries.just(0)),
                        net.jqwik.api.Tuple.of(4, Arbitraries.integers().between(1, 5)),
                        net.jqwik.api.Tuple.of(3, Arbitraries.integers().between(-5, -1)))
                .list().ofSize(30);
    }

    @Property(tries = 2_000)
    @Label("con un reloj que salta hacia atrás: updatedAt nunca retrocede, el pago siempre se recarga y el tiempo se comprueba el último")
    void clockJitterNeverBreaksAggregate(@ForAll @LongRange(min = 1, max = 100_000) long amount,
                                         @ForAll("operations") List<Op> ops,
                                         @ForAll("jitters") List<Integer> jitters) {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), request(amount), T0);
        p.pullEvents();
        Instant clock = T0;

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            clock = clock.plusSeconds(jitters.get(i));
            Instant now = clock;
            PaymentTransitionMatrixTest.Snapshot before = PaymentTransitionMatrixTest.Snapshot.of(p);
            Instant lastUpdate = p.updatedAt();
            try {
                apply(p, op, now);
                assertThat(now).as("aceptada %s con un instante anterior a updatedAt", op).isAfterOrEqualTo(lastUpdate);
                assertThat(p.updatedAt()).isEqualTo(now);
            } catch (NonMonotonicTimeException e) {
                assertThat(now).isBefore(lastUpdate);
                assertThat(PaymentTransitionMatrixTest.Snapshot.of(p)).isEqualTo(before);
                // El tiempo es lo último que se comprueba: la misma operación con un instante válido sale bien.
                Payment twin = reload(p);
                assertThatCode(() -> apply(twin, op, lastUpdate)).as("%s con now = updatedAt", op)
                        .doesNotThrowAnyException();
            } catch (DomainException e) {
                assertThat(PaymentTransitionMatrixTest.Snapshot.of(p)).isEqualTo(before);
                // Y cualquier otro rechazo no depende del reloj: con now = updatedAt se rechaza igual.
                Payment twin = reload(p);
                assertThatThrownBy(() -> apply(twin, op, lastUpdate)).isExactlyInstanceOf(e.getClass());
            }
            assertThat(p.updatedAt()).isAfterOrEqualTo(lastUpdate);
            p.pullEvents();
            assertInvariants(p);
        }
        reload(p);
    }

    private static Payment reload(Payment p) {
        return Payment.rehydrate(p.id(), p.request(), p.status(), p.attempts(), p.authorizedBy().orElse(null),
                p.gatewayReference().orElse(null), p.failureReason().orElse(null), p.refundedAmount(),
                p.createdAt(), p.updatedAt());
    }

    // ---------------------------------------------------------------------------------
    // Reembolsos enormes
    // ---------------------------------------------------------------------------------

    @Property(tries = 1_000)
    @Label("Long.MAX_VALUE tras cualquier serie de reembolsos parciales es RefundExceedsCaptured con lo que queda")
    void maxRefundAfterPartials(@ForAll @LongRange(min = 2, max = Long.MAX_VALUE) long amount,
                                @ForAll @Size(min = 1, max = 6) List<@LongRange(min = 1, max = Long.MAX_VALUE) Long> raw) {
        Payment p = captured(amount);
        long refunded = 0;
        Instant t = T0.plusSeconds(10);
        for (long r : raw) {
            long remaining = amount - refunded;
            long part = 1 + Math.floorMod(r, remaining - 1 == 0 ? 1 : remaining - 1); // deja al menos 1
            if (part >= remaining) {
                break;
            }
            p.refund(Money.of(part, Currency.EUR), t);
            refunded += part;
        }
        p.pullEvents();
        long remaining = amount - refunded;
        PaymentTransitionMatrixTest.Snapshot before = PaymentTransitionMatrixTest.Snapshot.of(p);

        if (remaining == Long.MAX_VALUE) {
            p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), t);
            assertThat(p.status()).isEqualTo(REFUNDED);
            return;
        }
        assertThatThrownBy(() -> p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), t))
                .isInstanceOfSatisfying(RefundExceedsCapturedException.class,
                        e -> assertThat(e.remainingRefundable()).isEqualTo(Money.of(remaining, Currency.EUR)));
        assertThat(PaymentTransitionMatrixTest.Snapshot.of(p)).isEqualTo(before);
        assertThat(p.pullEvents()).isEmpty();
        assertThat(p.remainingRefundable()).isEqualTo(Money.of(remaining, Currency.EUR));
        p.refund(Money.of(remaining, Currency.EUR), t);
        assertThat(p.status()).isEqualTo(REFUNDED);
        assertThat(p.refundedAmount()).isEqualTo(Money.of(amount, Currency.EUR));
    }

    @Example
    @Label("Long.MAX_VALUE en estados que no admiten reembolso es ILLEGAL_TRANSITION, no un desbordamiento")
    void maxRefundInNonRefundableStates() {
        for (PaymentStatus s : PaymentStatus.values()) {
            if (s == CAPTURED || s == PARTIALLY_REFUNDED) {
                continue;
            }
            Payment p = PaymentTransitionMatrixTest.reachedByBusinessMethods(s);
            assertThatThrownBy(() -> p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), T0.plusSeconds(100)))
                    .as(s.name()).isInstanceOf(IllegalPaymentTransitionException.class);
        }
    }

    @Example
    @Label("un pago de Long.MAX_VALUE: tras reembolsar 1, Long.MAX_VALUE es de más y Long.MAX_VALUE - 1 lo completa")
    void maxPaymentEdge() {
        Payment p = captured(Long.MAX_VALUE);
        p.refund(Money.of(1, Currency.EUR), T0.plusSeconds(10));
        assertThatThrownBy(() -> p.refund(Money.of(Long.MAX_VALUE, Currency.EUR), T0.plusSeconds(11)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        p.refund(Money.of(Long.MAX_VALUE - 1, Currency.EUR), T0.plusSeconds(12));
        assertThat(p.status()).isEqualTo(REFUNDED);
        assertThat(p.refundedAmount()).isEqualTo(Money.of(Long.MAX_VALUE, Currency.EUR));
    }

    // ---------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------

    private static Payment captured(long amount) {
        Payment p = Payment.create(PaymentId.of(UUID.randomUUID()), request(amount), T0);
        p.authorize(GW, "ref-1", T0.plusSeconds(1));
        p.capture(T0.plusSeconds(2));
        p.pullEvents();
        return p;
    }

    /** Reparte {@code total} en partes positivas proporcionales a los pesos y que suman exactamente {@code total}. */
    private static List<Long> split(long total, List<Integer> weights) {
        int n = (int) Math.min(weights.size(), total);
        long weightSum = weights.subList(0, n).stream().mapToLong(Integer::longValue).sum();
        List<Long> parts = new ArrayList<>();
        long assigned = 0;
        for (int i = 0; i < n - 1; i++) {
            long part = Math.max(1, (total - (n - 1 - i) - assigned) * weights.get(i) / weightSum);
            part = Math.min(part, total - assigned - (n - 1 - i));
            parts.add(part);
            assigned += part;
        }
        parts.add(total - assigned);
        assertThat(parts).allMatch(x -> x > 0);
        assertThat(parts.stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
        return parts;
    }
}
