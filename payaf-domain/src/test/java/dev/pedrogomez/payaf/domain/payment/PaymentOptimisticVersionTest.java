package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tester (fase 4a): la versión optimista del agregado. Un pago nuevo nace con versión 0,
 * {@code markPersisted} solo deja avanzar y {@code rehydrate} conserva la versión cargada.
 */
class PaymentOptimisticVersionTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    private static Payment newPayment() {
        PaymentRequest request = new PaymentRequest(Money.of(10_000, Currency.EUR), CountryCode.of("ES"),
                "pedido-1", IdempotencyKey.of("k-" + UUID.randomUUID()));
        return Payment.create(PaymentId.of(UUID.randomUUID()), request, T0);
    }

    private static Payment rehydrateWith(Payment p, long version) {
        return Payment.rehydrate(p.id(), p.request(), p.status(), p.attempts(), p.authorizedBy().orElse(null),
                p.gatewayReference().orElse(null), p.failureReason().orElse(null), p.refundedAmount(), p.refunds(),
                p.createdAt(), p.updatedAt(), version);
    }

    @Test
    @DisplayName("un pago recién creado tiene versión 0")
    void newPaymentStartsAtZero() {
        assertThat(newPayment().version()).isZero();
    }

    @Test
    @DisplayName("markPersisted avanza la versión y no toca estado, eventos ni updatedAt")
    void markPersistedOnlyMovesTheVersion() {
        Payment payment = newPayment();
        Instant updatedAt = payment.updatedAt();

        payment.markPersisted(1);
        payment.markPersisted(7);

        assertThat(payment.version()).isEqualTo(7);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.updatedAt()).isEqualTo(updatedAt);
        // Solo el PaymentCreated de la creación: markPersisted no emite nada.
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(dev.pedrogomez.payaf.domain.event.PaymentCreated.class);
    }

    @ParameterizedTest(name = "de 5 a {0} se rechaza")
    @ValueSource(longs = {5, 4, 0, -1, Long.MIN_VALUE})
    @DisplayName("markPersisted exige newVersion > version y, si no, la versión no cambia")
    void markPersistedRequiresStrictlyGreater(long newVersion) {
        Payment payment = newPayment();
        payment.markPersisted(5);

        assertThatThrownBy(() -> payment.markPersisted(newVersion)).isInstanceOf(InvalidValueException.class);
        assertThat(payment.version()).isEqualTo(5);
    }

    @ParameterizedTest(name = "versión {0}")
    @ValueSource(longs = {0, 1, 42, Long.MAX_VALUE})
    @DisplayName("rehydrate conserva la versión, también en un pago autorizado")
    void rehydrateKeepsVersion(long version) {
        Payment payment = newPayment();
        payment.recordAttempt(new GatewayAttempt(GatewayId.of("stripe"), AttemptOutcome.APPROVED,
                java.time.Duration.ofMillis(5), java.util.Optional.empty(), java.util.Optional.of("pi_1"), T0));
        payment.authorize(GatewayId.of("stripe"), "pi_1", T0);

        Payment copy = rehydrateWith(payment, version);

        assertThat(copy.version()).isEqualTo(version);
        assertThat(copy.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(copy.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("rehydrate rechaza una versión negativa")
    void rehydrateRejectsNegativeVersion() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> rehydrateWith(payment, -1)).isInstanceOf(InconsistentPaymentStateException.class);
    }

    @Test
    @DisplayName("la versión no forma parte de la identidad: dos versiones del mismo pago son el mismo pago")
    void versionIsNotIdentity() {
        Payment payment = newPayment();
        assertThat(rehydrateWith(payment, 3)).isEqualTo(rehydrateWith(payment, 9));
        assertThat(List.of(rehydrateWith(payment, 3))).contains(payment);
    }
}
