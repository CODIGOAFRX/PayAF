package dev.pedrogomez.payaf.domain.payment;

import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.AUTHORIZED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CANCELED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.CAPTURED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.FAILED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.PENDING;
import static dev.pedrogomez.payaf.domain.payment.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Matriz completa 7×7 de la máquina de estados del pago, contra una tabla escrita a mano a
 * partir de la especificación (no a partir de {@link PaymentStatus#allowedTransitions()}).
 * Se recorre dos veces: en el enum y a través de los métodos de negocio de {@link Payment},
 * partiendo tanto de pagos construidos con métodos de negocio como de pagos rehidratados.
 */
class PaymentTransitionMatrixTest {

    /** La especificación, transcrita tal cual. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> SPEC = new EnumMap<>(Map.of(
            PENDING, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, CANCELED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class)));

    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T_OP = T0.plusSeconds(100);
    private static final GatewayId GW = GatewayId.of("adyen");
    private static final long AMOUNT = 1_000;

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                pairs.add(Arguments.of(from, to, SPEC.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    static Stream<Arguments> allPairsTwoOrigins() {
        return allPairs().flatMap(a -> Stream.of(
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], false),
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], true)));
    }

    @Test
    @DisplayName("la matriz recorre los 7 estados de la especificación, ni uno más")
    void matrixCoversEveryState() {
        assertThat(PaymentStatus.values()).containsExactlyInAnyOrderElementsOf(SPEC.keySet());
        assertThat(allPairs().count()).isEqualTo(49);
    }

    @ParameterizedTest(name = "[enum] {0} → {1}: permitida = {2}")
    @MethodSource("allPairs")
    void enumMatrix(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        assertThat(from.allowedTransitions().contains(to)).isEqualTo(allowed);
        assertThat(from.isTerminal()).isEqualTo(SPEC.get(from).isEmpty());
    }

    @Test
    @DisplayName("allowedTransitions devuelve una copia: modificarla no cambia la máquina de estados")
    void allowedTransitionsIsDefensiveCopy() {
        PENDING.allowedTransitions().add(CAPTURED);
        PENDING.allowedTransitions().clear();
        assertThat(PENDING.canTransitionTo(CAPTURED)).isFalse();
        assertThat(PENDING.canTransitionTo(AUTHORIZED)).isTrue();
    }

    @Test
    @DisplayName("isCaptured es cierto justo en los estados con dinero cobrado")
    void isCaptured() {
        for (PaymentStatus s : PaymentStatus.values()) {
            assertThat(s.isCaptured()).as(s.name())
                    .isEqualTo(EnumSet.of(CAPTURED, PARTIALLY_REFUNDED, REFUNDED).contains(s));
        }
    }

    @ParameterizedTest(name = "[Payment, rehidratado={3}] {0} → {1}: permitida = {2}")
    @MethodSource("allPairsTwoOrigins")
    void paymentMatrix(PaymentStatus from, PaymentStatus to, boolean allowed, boolean rehydrated) {
        Payment payment = rehydrated ? rehydratedIn(from) : reachedByBusinessMethods(from);
        payment.pullEvents();
        assertThat(payment.status()).isEqualTo(from);

        if (to == PENDING) {
            // Ningún método de negocio lleva a PENDING: probamos todos y ninguno puede acabar ahí
            // saliendo de otro estado.
            for (Op op : Op.values()) {
                Payment fresh = rehydrated ? rehydratedIn(from) : reachedByBusinessMethods(from);
                fresh.pullEvents();
                try {
                    op.apply(fresh, PENDING);
                } catch (RuntimeException ignored) {
                    // Da igual que falle; lo que importa es dónde queda.
                }
                if (from != PENDING) {
                    assertThat(fresh.status()).as(op + " desde " + from).isNotEqualTo(PENDING);
                }
            }
            assertThat(allowed).isFalse();
            return;
        }

        Snapshot before = Snapshot.of(payment);
        Op op = Op.leadingTo(to);
        if (allowed) {
            op.apply(payment, to);
            assertThat(payment.status()).isEqualTo(to);
            assertThat(payment.updatedAt()).isEqualTo(T_OP);
            List<DomainEvent> events = payment.pullEvents();
            assertThat(events).singleElement().isInstanceOf(op.eventType);
        } else {
            assertThatThrownBy(() -> op.apply(payment, to))
                    .isInstanceOfSatisfying(IllegalPaymentTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        if (from == REFUNDED && to == REFUNDED) {
                            // Desde REFUNDED todo reembolso positivo supera lo capturado, así que el
                            // destino que se intenta es un reembolso más; lo que importa es que se
                            // rechace como transición ilegal y no como otra cosa.
                            assertThat(e.to()).isIn(PARTIALLY_REFUNDED, REFUNDED);
                        } else {
                            assertThat(e.to()).isEqualTo(to);
                        }
                        assertThat(e.paymentId()).isEqualTo(payment.id());
                        assertThat(e.code()).isEqualTo("ILLEGAL_TRANSITION");
                    });
            assertThat(Snapshot.of(payment)).as("un rechazo no deja rastro").isEqualTo(before);
            assertThat(payment.pullEvents()).isEmpty();
        }
    }

    @ParameterizedTest(name = "recordAttempt en {0}")
    @MethodSource("statuses")
    @DisplayName("recordAttempt solo se admite en PENDING, y en el resto no deja rastro")
    void recordAttemptOnlyInPending(PaymentStatus from) {
        Payment payment = reachedByBusinessMethods(from);
        payment.pullEvents();
        GatewayAttempt attempt = new GatewayAttempt(GW, AttemptOutcome.ERROR, Duration.ZERO,
                Optional.empty(), Optional.empty(), T_OP);
        if (from == PENDING) {
            payment.recordAttempt(attempt);
            assertThat(payment.status()).isEqualTo(PENDING);
            assertThat(payment.attempts()).containsExactly(attempt);
        } else {
            Snapshot before = Snapshot.of(payment);
            assertThatThrownBy(() -> payment.recordAttempt(attempt))
                    .isInstanceOfSatisfying(IllegalPaymentTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.to()).isEqualTo(from);
                    });
            assertThat(Snapshot.of(payment)).isEqualTo(before);
            assertThat(payment.pullEvents()).isEmpty();
        }
    }

    static Stream<PaymentStatus> statuses() {
        return Stream.of(PaymentStatus.values());
    }

    // ---------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------

    /** La operación de negocio cuyo destino natural es cada estado. */
    enum Op {
        AUTHORIZE(PaymentAuthorized.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                p.authorize(GW, "psp-ref", T_OP);
            }
        },
        FAIL(PaymentFailed.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                p.fail("sin pasarelas", T_OP);
            }
        },
        CAPTURE(PaymentCaptured.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                p.capture(T_OP);
            }
        },
        CANCEL(PaymentCanceled.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                p.cancel(T_OP);
            }
        },
        REFUND_PART(PaymentRefunded.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                p.refund(Money.of(1, Currency.EUR), TestRefs.next(), T_OP);
            }
        },
        REFUND_ALL(PaymentRefunded.class) {
            @Override
            void apply(Payment p, PaymentStatus target) {
                // Todo lo que queda; si no se ha capturado nada, el importe entero. En REFUNDED
                // no queda nada y cualquier reembolso positivo es ya un reembolso de más.
                Money rest = p.amount().minus(p.refundedAmount());
                p.refund(rest.isPositive() ? rest : Money.of(1, Currency.EUR), TestRefs.next(), T_OP);
            }
        };

        final Class<? extends DomainEvent> eventType;

        Op(Class<? extends DomainEvent> eventType) {
            this.eventType = eventType;
        }

        abstract void apply(Payment p, PaymentStatus target);

        static Op leadingTo(PaymentStatus target) {
            return switch (target) {
                case AUTHORIZED -> AUTHORIZE;
                case FAILED -> FAIL;
                case CAPTURED -> CAPTURE;
                case CANCELED -> CANCEL;
                case PARTIALLY_REFUNDED -> REFUND_PART;
                case REFUNDED -> REFUND_ALL;
                case PENDING -> throw new IllegalArgumentException("ninguna operación lleva a PENDING");
            };
        }
    }

    private static PaymentRequest request() {
        return new PaymentRequest(Money.of(AMOUNT, Currency.EUR), CountryCode.of("ES"), "pedido-m",
                IdempotencyKey.of("k-m"));
    }

    private static PaymentId id() {
        return PaymentId.of(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
    }

    static Payment reachedByBusinessMethods(PaymentStatus status) {
        Payment p = Payment.create(id(), request(), T0);
        Instant t = T0.plusSeconds(1);
        switch (status) {
            case PENDING -> { }
            case FAILED -> p.fail("todas caídas", t);
            case AUTHORIZED -> p.authorize(GW, "ref-1", t);
            case CANCELED -> {
                p.authorize(GW, "ref-1", t);
                p.cancel(t);
            }
            case CAPTURED -> {
                p.authorize(GW, "ref-1", t);
                p.capture(t);
            }
            case PARTIALLY_REFUNDED -> {
                p.authorize(GW, "ref-1", t);
                p.capture(t);
                p.refund(Money.of(300, Currency.EUR), TestRefs.next(), t);
            }
            case REFUNDED -> {
                p.authorize(GW, "ref-1", t);
                p.capture(t);
                p.refund(Money.of(AMOUNT, Currency.EUR), TestRefs.next(), t);
            }
        }
        return p;
    }

    static Payment rehydratedIn(PaymentStatus status) {
        boolean authorized = status != PENDING && status != FAILED;
        long refunded = switch (status) {
            case PARTIALLY_REFUNDED -> 300;
            case REFUNDED -> AMOUNT;
            default -> 0;
        };
        return Payment.rehydrate(id(), request(), status, List.of(),
                authorized ? GW : null, authorized ? "ref-1" : null, status == FAILED ? "todas caídas" : null,
                Money.of(refunded, Currency.EUR), TestRefs.refundsFor(Money.of(refunded, Currency.EUR)), T0, T0.plusSeconds(1));
    }

    /** Todo lo observable del pago, para comprobar que una operación rechazada no lo toca. */
    record Snapshot(PaymentStatus status, Money refunded, Money remaining, Instant updatedAt, int attempts,
                    Optional<GatewayId> authorizedBy, Optional<String> reference, Optional<String> failure) {
        static Snapshot of(Payment p) {
            return new Snapshot(p.status(), p.refundedAmount(), p.remainingRefundable(), p.updatedAt(),
                    p.attempts().size(), p.authorizedBy(), p.gatewayReference(), p.failureReason());
        }
    }
}
