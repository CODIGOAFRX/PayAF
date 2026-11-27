package dev.pedrogomez.payaf.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final Instant T2 = T0.plusSeconds(2);
    private static final PaymentId ID = PaymentId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final GatewayId STRIPE = GatewayId.of("stripe");

    private static PaymentRequest request(long minor) {
        return new PaymentRequest(Money.of(minor, Currency.EUR), CountryCode.of("ES"), "pedido-1",
                IdempotencyKey.of("clave-1"));
    }

    private static Payment pending() {
        Payment payment = Payment.create(ID, request(1000), T0);
        payment.pullEvents();
        return payment;
    }

    private static Payment authorized() {
        Payment payment = pending();
        payment.authorize(STRIPE, "ch_1", T1);
        payment.pullEvents();
        return payment;
    }

    private static Payment captured() {
        Payment payment = authorized();
        payment.capture(T1);
        payment.pullEvents();
        return payment;
    }

    private static GatewayAttempt attempt(AttemptOutcome outcome) {
        return new GatewayAttempt(STRIPE, outcome, Duration.ofMillis(120), Optional.empty(), Optional.empty(), T1);
    }

    @Test
    @DisplayName("create deja el pago en PENDING y emite PaymentCreated")
    void createIsPending() {
        Payment payment = Payment.create(ID, request(1000), T0);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.createdAt()).isEqualTo(T0);
        assertThat(payment.authorizedBy()).isEmpty();
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(PaymentCreated.class);
        assertThat(payment.pullEvents()).as("pullEvents vacía la lista").isEmpty();
    }

    @Test
    @DisplayName("la orden exige un importe positivo")
    void requestNeedsPositiveAmount() {
        assertThatThrownBy(() -> request(0)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> request(-5)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("la huella es estable y cambia si cambia el contenido, no la clave")
    void fingerprint() {
        PaymentRequest a = request(1000);
        PaymentRequest sameContentOtherKey = new PaymentRequest(a.amount(), a.country(), a.merchantReference(),
                IdempotencyKey.of("otra"));

        assertThat(a.fingerprint()).isEqualTo(request(1000).fingerprint());
        assertThat(a.fingerprint()).isEqualTo(sameContentOtherKey.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(request(1001).fingerprint());
        assertThat(a.fingerprint().value()).hasSize(64);
    }

    @Test
    @DisplayName("PENDING: registra intentos y se autoriza; no se puede capturar")
    void pendingTransitions() {
        Payment payment = pending();
        payment.recordAttempt(attempt(AttemptOutcome.TIMEOUT));
        payment.authorize(STRIPE, "ch_1", T2);

        assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.authorizedBy()).contains(STRIPE);
        assertThat(payment.attempts()).hasSize(1);
        assertThat(payment.updatedAt()).isEqualTo(T2);
        assertThat(payment.pullEvents()).hasExactlyElementsOfTypes(
                GatewayAttemptRecorded.class, PaymentAuthorized.class);
        assertThatThrownBy(() -> payment.attempts().add(attempt(AttemptOutcome.ERROR)))
                .isInstanceOf(UnsupportedOperationException.class);

        Payment other = pending();
        assertThatThrownBy(() -> other.capture(T1))
                .isInstanceOfSatisfying(IllegalPaymentTransitionException.class, e -> {
                    assertThat(e.from()).isEqualTo(PaymentStatus.PENDING);
                    assertThat(e.to()).isEqualTo(PaymentStatus.CAPTURED);
                    assertThat(e.code()).isEqualTo("ILLEGAL_TRANSITION");
                });
    }

    @Test
    @DisplayName("PENDING → FAILED, y FAILED es terminal")
    void failedIsTerminal() {
        Payment payment = pending();
        payment.fail("todas las pasarelas han fallado", T1);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureReason()).contains("todas las pasarelas han fallado");
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(PaymentFailed.class);
        assertThatThrownBy(() -> payment.authorize(STRIPE, "ch_1", T2))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("AUTHORIZED: se captura por el total; no admite más intentos")
    void authorizedTransitions() {
        Payment payment = authorized();
        assertThatThrownBy(() -> payment.recordAttempt(attempt(AttemptOutcome.APPROVED)))
                .isInstanceOf(IllegalPaymentTransitionException.class);

        payment.capture(T2);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.capturedAmount()).isEqualTo(Money.of(1000, Currency.EUR));
        assertThat(payment.remainingRefundable()).isEqualTo(Money.of(1000, Currency.EUR));
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(PaymentCaptured.class);
    }

    @Test
    @DisplayName("AUTHORIZED → CANCELED, y CANCELED es terminal")
    void canceledIsTerminal() {
        Payment payment = authorized();
        payment.cancel(T2);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(PaymentCanceled.class);
        assertThatThrownBy(() -> payment.capture(T2)).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("CAPTURED: reembolso parcial, otro parcial y el que completa pasa a REFUNDED")
    void refunds() {
        Payment payment = captured();
        assertThatThrownBy(() -> payment.cancel(T2)).isInstanceOf(IllegalPaymentTransitionException.class);

        payment.refund(Money.of(300, Currency.EUR), TestRefs.next(), T2);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        payment.refund(Money.of(200, Currency.EUR), TestRefs.next(), T2);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.refundedAmount()).isEqualTo(Money.of(500, Currency.EUR));
        assertThat(payment.remainingRefundable()).isEqualTo(Money.of(500, Currency.EUR));

        payment.refund(Money.of(500, Currency.EUR), TestRefs.next(), T2);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.remainingRefundable().isZero()).isTrue();

        List<?> events = payment.pullEvents();
        assertThat(events).hasSize(3).allMatch(PaymentRefunded.class::isInstance);
        assertThat(((PaymentRefunded) events.get(2)).totalRefunded()).isEqualTo(Money.of(1000, Currency.EUR));

        assertThatThrownBy(() -> payment.refund(Money.of(1, Currency.EUR), TestRefs.next(), T2))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("el reembolso no puede superar lo capturado ni ir en otra divisa ni ser cero")
    void invalidRefunds() {
        Payment payment = captured();
        payment.refund(Money.of(600, Currency.EUR), TestRefs.next(), T2);

        assertThatThrownBy(() -> payment.refund(Money.of(401, Currency.EUR), TestRefs.next(), T2))
                .isInstanceOfSatisfying(RefundExceedsCapturedException.class,
                        e -> assertThat(e.remainingRefundable()).isEqualTo(Money.of(400, Currency.EUR)));
        assertThatThrownBy(() -> payment.refund(Money.of(100, Currency.USD), TestRefs.next(), T2))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> payment.refund(Money.zero(Currency.EUR), TestRefs.next(), T2))
                .isInstanceOf(InvalidValueException.class);
        assertThat(payment.refundedAmount()).isEqualTo(Money.of(600, Currency.EUR));

        assertThatThrownBy(() -> pending().refund(Money.of(1, Currency.EUR), TestRefs.next(), T2))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    @DisplayName("rehydrate reconstruye sin eventos y valida los invariantes")
    void rehydrate() {
        Payment payment = Payment.rehydrate(ID, request(1000), PaymentStatus.PARTIALLY_REFUNDED,
                List.of(attempt(AttemptOutcome.APPROVED)), STRIPE, "ch_1", null,
                Money.of(400, Currency.EUR), TestRefs.refundsFor(Money.of(400, Currency.EUR)), T0, T2);

        assertThat(payment.pullEvents()).isEmpty();
        assertThat(payment.remainingRefundable()).isEqualTo(Money.of(600, Currency.EUR));

        assertThatThrownBy(() -> Payment.rehydrate(ID, request(1000), PaymentStatus.REFUNDED, List.of(),
                STRIPE, "ch_1", null, Money.of(400, Currency.EUR), TestRefs.refundsFor(Money.of(400, Currency.EUR)), T0, T2))
                .isInstanceOf(InconsistentPaymentStateException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, request(1000), PaymentStatus.AUTHORIZED, List.of(),
                null, null, null, Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T2))
                .isInstanceOf(InconsistentPaymentStateException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, request(1000), PaymentStatus.PENDING, List.of(),
                null, null, null, Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T2, T0))
                .isInstanceOf(InconsistentPaymentStateException.class);
    }

    @Test
    @DisplayName("un reembolso de Long.MAX_VALUE es RefundExceedsCaptured, no un desbordamiento")
    void hugeRefundIsExceeds() {
        Payment payment = captured();
        payment.refund(Money.of(1, Currency.EUR), TestRefs.next(), T2);
        assertThatThrownBy(() -> payment.refund(Money.of(Long.MAX_VALUE, Currency.EUR), TestRefs.next(), T2))
                .isInstanceOf(RefundExceedsCapturedException.class);
    }

    @Test
    @DisplayName("el tiempo es monótono: un instante anterior a updatedAt se rechaza y uno igual vale")
    void monotonicTime() {
        Payment payment = pending();
        assertThatThrownBy(() -> payment.authorize(STRIPE, "ch_1", T0.minusSeconds(1)))
                .isInstanceOfSatisfying(NonMonotonicTimeException.class,
                        e -> assertThat(e.code()).isEqualTo("NON_MONOTONIC_TIME"));
        assertThatThrownBy(() -> payment.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.ERROR,
                Duration.ZERO, Optional.empty(), Optional.empty(), T0.minusMillis(1))))
                .isInstanceOf(NonMonotonicTimeException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.attempts()).isEmpty();
        assertThat(payment.pullEvents()).isEmpty();

        payment.authorize(STRIPE, "ch_1", T0);
        payment.capture(T2);
        assertThatThrownBy(() -> payment.refund(Money.of(1, Currency.EUR), TestRefs.next(), T1))
                .isInstanceOf(NonMonotonicTimeException.class);
        assertThat(payment.refundedAmount().isZero()).isTrue();
    }

    @Test
    @DisplayName("rehydrate rechaza referencia o motivo en blanco")
    void rehydrateRejectsBlankTexts() {
        assertThatThrownBy(() -> Payment.rehydrate(ID, request(1000), PaymentStatus.FAILED, List.of(),
                null, null, "  ", Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T2))
                .isInstanceOf(InconsistentPaymentStateException.class);
        assertThatThrownBy(() -> Payment.rehydrate(ID, request(1000), PaymentStatus.AUTHORIZED, List.of(),
                STRIPE, "", null, Money.zero(Currency.EUR), TestRefs.refundsFor(Money.zero(Currency.EUR)), T0, T2))
                .isInstanceOf(InconsistentPaymentStateException.class);
    }
}
