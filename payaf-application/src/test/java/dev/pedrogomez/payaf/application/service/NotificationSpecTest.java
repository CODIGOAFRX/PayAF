package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.CANCEL;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.CAPTURE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.REFUND;
import static dev.pedrogomez.payaf.application.service.Harness.ADYEN;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.NotificationResult;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.Refund;
import dev.pedrogomez.payaf.domain.payment.RefundExceedsCapturedException;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Especificación de {@link HandleGatewayNotificationService}.
 * <p>
 * El {@code FakeTransactionRunner} original no deshacía nada, así que con él una
 * notificación rechazada por el dominio dejaba la marca puesta y la segunda entrega daba
 * DUPLICATE aunque nunca se hubiera aplicado. Ahora simula el rollback (ver el fake) y estos
 * tests comprueban que la marca desaparece.
 */
class NotificationSpecTest {

    private final Harness h = new Harness();

    @AfterEach
    void transactionDisciplineOnEveryPath() {
        h.assertTransactionDiscipline();
    }

    private PaymentId authorizedOnStripe() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
        return h.authorize("k1").payment().id();
    }

    private PaymentId capturedOnStripe() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CAPTURE, GatewayResponse.approved("ch_1", LATENCY));
        h.operations.capture(id);
        return id;
    }

    private PaymentId canceledOnStripe() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CANCEL, GatewayResponse.approved("ch_1", LATENCY));
        h.operations.cancel(id);
        return id;
    }

    private GatewayNotification notification(String id, NotificationType type) {
        return notification(id, "ch_1", type);
    }

    private GatewayNotification notification(String id, String reference, NotificationType type) {
        return new GatewayNotification(STRIPE, id, reference, type, Optional.empty(), Optional.empty(),
                h.clock.instant());
    }

    private GatewayNotification refundConfirmed(String id, String refundReference, Money amount) {
        return new GatewayNotification(STRIPE, id, "ch_1", NotificationType.REFUND_CONFIRMED, Optional.of(amount),
                Optional.of(refundReference), h.clock.instant());
    }

    private static Money eur(long minor) {
        return Money.of(minor, Currency.EUR);
    }

    /** La notificación ha lanzado y su marca se ha deshecho: la segunda entrega tampoco es DUPLICATE. */
    private void assertRolledBackAndRetriable(GatewayNotification n, Class<? extends DomainException> type,
                                              PaymentId id) {
        PaymentView before = h.queryView(id);
        int events = h.events.published().size();

        assertThatThrownBy(() -> h.notifications.handle(n)).isInstanceOf(type);
        assertThat(h.processed.isProcessed(n.gateway(), n.notificationId()))
                .as("la marca debe deshacerse con la transacción").isFalse();
        // Segunda entrega: vuelve a intentarlo (y vuelve a fallar), no la descarta como DUPLICATE.
        assertThatThrownBy(() -> h.notifications.handle(n)).isInstanceOf(type);
        assertThat(h.queryView(id)).isEqualTo(before);
        assertThat(h.events.published()).hasSize(events);
    }

    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("la misma notificationId dos veces: DUPLICATE, aunque la segunda traiga otro tipo")
    void duplicateById() {
        PaymentId id = authorizedOnStripe();

        assertThat(h.notifications.handle(notification("evt_1", NotificationType.CAPTURE_CONFIRMED)))
                .isEqualTo(NotificationResult.APPLIED);
        assertThat(h.notifications.handle(notification("evt_1", NotificationType.AUTHORIZATION_REVERSED)))
                .isEqualTo(NotificationResult.DUPLICATE);
        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("la misma notificationId de otra pasarela no es un duplicado")
    void sameIdOtherGatewayIsNotDuplicate() {
        authorizedOnStripe();
        h.notifications.handle(notification("evt_1", NotificationType.CAPTURE_CONFIRMED));

        GatewayNotification fromAdyen = new GatewayNotification(ADYEN, "evt_1", "ch_1",
                NotificationType.CAPTURE_CONFIRMED, Optional.empty(), Optional.empty(), h.clock.instant());

        // Adyen no tiene ningún pago con la referencia ch_1 (es de Stripe).
        assertThat(h.notifications.handle(fromAdyen)).isEqualTo(NotificationResult.UNKNOWN_PAYMENT);
    }

    @Test
    @DisplayName("referencia desconocida: UNKNOWN_PAYMENT y la notificación queda marcada como procesada")
    void unknownPaymentIsMarked() {
        authorizedOnStripe();
        GatewayNotification n = notification("evt_x", "ch_nope", NotificationType.AUTHORIZATION_REVERSED);

        assertThat(h.notifications.handle(n)).isEqualTo(NotificationResult.UNKNOWN_PAYMENT);
        assertThat(h.processed.isProcessed(STRIPE, "evt_x")).isTrue();
        assertThat(h.notifications.handle(n)).isEqualTo(NotificationResult.DUPLICATE);
    }

    // CAPTURE_CONFIRMED -----------------------------------------------------------------

    @Test
    @DisplayName("CAPTURE_CONFIRMED sobre AUTHORIZED: APPLIED, CAPTURED y un PaymentCaptured")
    void captureConfirmedOnAuthorized() {
        PaymentId id = authorizedOnStripe();

        assertThat(h.notifications.handle(notification("evt_1", NotificationType.CAPTURE_CONFIRMED)))
                .isEqualTo(NotificationResult.APPLIED);
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.events.published(PaymentCaptured.class)).hasSize(1);
        assertThat(h.stripe.calls(CAPTURE)).isZero();
    }

    @Test
    @DisplayName("CAPTURE_CONFIRMED sobre CAPTURED, PARTIALLY_REFUNDED y REFUNDED: ALREADY_IN_STATE sin cambios")
    void captureConfirmedWhenAlreadyCaptured() {
        PaymentId id = capturedOnStripe();
        assertAlreadyInStateWithoutChanges(id, notification("evt_c1", NotificationType.CAPTURE_CONFIRMED));

        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY), GatewayResponse.approved("re_2", LATENCY));
        h.operations.refund(id, eur(4_000));
        assertAlreadyInStateWithoutChanges(id, notification("evt_c2", NotificationType.CAPTURE_CONFIRMED));

        h.operations.refund(id, eur(6_000));
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertAlreadyInStateWithoutChanges(id, notification("evt_c3", NotificationType.CAPTURE_CONFIRMED));
    }

    private void assertAlreadyInStateWithoutChanges(PaymentId id, GatewayNotification n) {
        PaymentView before = h.queryView(id);
        int events = h.events.published().size();
        assertThat(h.notifications.handle(n)).isEqualTo(NotificationResult.ALREADY_IN_STATE);
        assertThat(h.queryView(id)).isEqualTo(before);
        assertThat(h.events.published()).hasSize(events);
        assertThat(h.processed.isProcessed(n.gateway(), n.notificationId())).isTrue();
    }

    @Test
    @DisplayName("CAPTURE_CONFIRMED sobre CANCELED: DomainException, rollback de la marca y la segunda entrega NO es DUPLICATE")
    void captureConfirmedOnCanceled() {
        PaymentId id = canceledOnStripe();

        assertRolledBackAndRetriable(notification("evt_1", NotificationType.CAPTURE_CONFIRMED),
                IllegalPaymentTransitionException.class, id);
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("CAPTURE_CONFIRMED sobre FAILED (adaptador que indexa también la referencia del intento): DomainException y rollback")
    void captureConfirmedOnFailed() {
        // Con el contrato de findByGatewayReference (referencia de la pasarela que AUTORIZÓ) un
        // pago FAILED nunca se encuentra: daría UNKNOWN_PAYMENT. Se prueba con un repositorio
        // que también busca por la referencia de los intentos, como podría hacer un adaptador.
        h.payments.matchAttemptReferences(true);
        h.stripe.on(AUTHORIZE, new GatewayResponse(dev.pedrogomez.payaf.domain.payment.AttemptOutcome.DECLINED,
                Optional.of("ch_dec"), Optional.of("do_not_honor"), LATENCY));
        PaymentId id = h.authorize("k1").payment().id();
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.FAILED);

        assertRolledBackAndRetriable(notification("evt_1", "ch_dec", NotificationType.CAPTURE_CONFIRMED),
                IllegalPaymentTransitionException.class, id);
        // Y una anulación sobre FAILED es ALREADY_IN_STATE (no está AUTHORIZED).
        assertThat(h.notifications.handle(notification("evt_2", "ch_dec", NotificationType.AUTHORIZATION_REVERSED)))
                .isEqualTo(NotificationResult.ALREADY_IN_STATE);
    }

    @Test
    @DisplayName("con el repositorio según su contrato, una notificación sobre un pago FAILED da UNKNOWN_PAYMENT")
    void failedPaymentIsNotFoundByContract() {
        h.stripe.on(AUTHORIZE, new GatewayResponse(dev.pedrogomez.payaf.domain.payment.AttemptOutcome.DECLINED,
                Optional.of("ch_dec"), Optional.empty(), LATENCY));
        h.authorize("k1");

        assertThat(h.notifications.handle(notification("evt_1", "ch_dec", NotificationType.CAPTURE_CONFIRMED)))
                .isEqualTo(NotificationResult.UNKNOWN_PAYMENT);
    }

    // REFUND_CONFIRMED ------------------------------------------------------------------

    @Test
    @DisplayName("REFUND_CONFIRMED con referencia conocida: ALREADY_IN_STATE y el total no cambia, también si el pago está REFUNDED")
    void refundConfirmedKnownReference() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_all", LATENCY));
        h.operations.refund(id, eur(10_000));
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.REFUNDED);

        assertAlreadyInStateWithoutChanges(id, refundConfirmed("evt_r1", "re_all", eur(10_000)));
        assertThat(h.queryView(id).refunded()).isEqualTo(eur(10_000));
        assertThat(h.events.published(PaymentRefunded.class)).hasSize(1);
    }

    @Test
    @DisplayName("REFUND_CONFIRMED con referencia nueva: APPLIED, se suma y queda con su referencia")
    void refundConfirmedNewReference() {
        PaymentId id = capturedOnStripe();

        assertThat(h.notifications.handle(refundConfirmed("evt_r1", "re_dash_1", eur(2_500))))
                .isEqualTo(NotificationResult.APPLIED);
        assertThat(h.notifications.handle(refundConfirmed("evt_r2", "re_dash_2", eur(7_500))))
                .isEqualTo(NotificationResult.APPLIED);

        PaymentView view = h.queryView(id);
        assertThat(view.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(view.refunded()).isEqualTo(eur(10_000));
        assertThat(view.refunds()).extracting(Refund::gatewayRefundReference).containsExactly("re_dash_1", "re_dash_2");
        assertThat(h.stripe.calls(REFUND)).isZero();
    }

    @Test
    @DisplayName("REFUND_CONFIRMED con una referencia conocida y otro importe: InvalidValueException y rollback de la marca")
    void refundConfirmedSameReferenceOtherAmount() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY));
        h.operations.refund(id, eur(3_000));

        assertRolledBackAndRetriable(refundConfirmed("evt_r1", "re_1", eur(3_001)), InvalidValueException.class, id);
        assertThat(h.queryView(id).refunded()).isEqualTo(eur(3_000));
    }

    @Test
    @DisplayName("REFUND_CONFIRMED nuevo que excede lo que queda: RefundExceedsCaptured y rollback")
    void refundConfirmedExceeding() {
        PaymentId id = capturedOnStripe();

        assertRolledBackAndRetriable(refundConfirmed("evt_r1", "re_big", eur(10_001)),
                RefundExceedsCapturedException.class, id);
    }

    @Test
    @DisplayName("REFUND_CONFIRMED sobre un pago solo autorizado: IllegalPaymentTransition y rollback")
    void refundConfirmedOnAuthorized() {
        PaymentId id = authorizedOnStripe();

        assertRolledBackAndRetriable(refundConfirmed("evt_r1", "re_1", eur(100)),
                IllegalPaymentTransitionException.class, id);
    }

    @Test
    @DisplayName("REFUND_CONFIRMED sin importe o con referencia en blanco no se puede construir")
    void refundConfirmedValidation() {
        assertThatThrownBy(() -> new GatewayNotification(STRIPE, "e", "ch_1", NotificationType.REFUND_CONFIRMED,
                Optional.empty(), Optional.of("re_1"), h.clock.instant())).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayNotification(STRIPE, "e", "ch_1", NotificationType.REFUND_CONFIRMED,
                Optional.of(eur(1)), Optional.of("   "), h.clock.instant())).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayNotification(STRIPE, " ", "ch_1", NotificationType.CAPTURE_CONFIRMED,
                Optional.empty(), Optional.empty(), h.clock.instant())).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayNotification(STRIPE, "e", "", NotificationType.CAPTURE_CONFIRMED,
                Optional.empty(), Optional.empty(), h.clock.instant())).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> h.notifications.handle(null)).isInstanceOf(NullPointerException.class);
    }

    // AUTHORIZATION_REVERSED -------------------------------------------------------------

    @Test
    @DisplayName("AUTHORIZATION_REVERSED sobre AUTHORIZED: APPLIED, CANCELED y un PaymentCanceled, sin llamar a la PSP")
    void reversalOnAuthorized() {
        PaymentId id = authorizedOnStripe();

        assertThat(h.notifications.handle(notification("evt_1", NotificationType.AUTHORIZATION_REVERSED)))
                .isEqualTo(NotificationResult.APPLIED);
        assertThat(h.queryView(id).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(h.events.published(PaymentCanceled.class)).hasSize(1);
        assertThat(h.stripe.calls(CANCEL)).isZero();
    }

    @Test
    @DisplayName("AUTHORIZATION_REVERSED sobre CAPTURED, PARTIALLY_REFUNDED o CANCELED: ALREADY_IN_STATE")
    void reversalOnOtherStates() {
        PaymentId captured = capturedOnStripe();
        assertAlreadyInStateWithoutChanges(captured, notification("evt_1", NotificationType.AUTHORIZATION_REVERSED));
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY));
        h.operations.refund(captured, eur(1));
        assertAlreadyInStateWithoutChanges(captured, notification("evt_2", NotificationType.AUTHORIZATION_REVERSED));

        Harness other = new Harness();
        other.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_9", LATENCY));
        PaymentId id = other.authorize("k9").payment().id();
        other.stripe.on(CANCEL, GatewayResponse.approved("ch_9", LATENCY));
        other.operations.cancel(id);
        GatewayNotification reversal = new GatewayNotification(STRIPE, "evt_9", "ch_9",
                NotificationType.AUTHORIZATION_REVERSED, Optional.empty(), Optional.empty(), other.clock.instant());
        PaymentView before = other.queryView(id);
        assertThat(other.notifications.handle(reversal)).isEqualTo(NotificationResult.ALREADY_IN_STATE);
        assertThat(other.queryView(id)).isEqualTo(before);
        other.assertTransactionDiscipline();
    }

    @Test
    @DisplayName("un rollback deshace la marca, y una vez corregido el estado la misma notificación se aplica")
    void rolledBackNotificationCanBeAppliedLater() {
        PaymentId id = authorizedOnStripe();
        GatewayNotification refund = refundConfirmed("evt_r1", "re_early", eur(500));

        // Llega el reembolso antes que la captura: el dominio lo rechaza y la marca se deshace.
        assertThatThrownBy(() -> h.notifications.handle(refund)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(h.notifications.handle(notification("evt_c", NotificationType.CAPTURE_CONFIRMED)))
                .isEqualTo(NotificationResult.APPLIED);

        // El reintento de la PSP ya encaja.
        assertThat(h.notifications.handle(refund)).isEqualTo(NotificationResult.APPLIED);
        assertThat(h.queryView(id).refunded()).isEqualTo(eur(500));
        assertThat(h.notifications.handle(refund)).isEqualTo(NotificationResult.DUPLICATE);
    }
}
