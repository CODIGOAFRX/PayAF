package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.CAPTURE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.REFUND;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.NotificationResult;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.Refund;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HandleGatewayNotificationServiceTest {

    private final Harness h = new Harness();

    @AfterEach
    void discipline() {
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

    private GatewayNotification notification(String id, NotificationType type) {
        return new GatewayNotification(STRIPE, id, "ch_1", type, Optional.empty(), Optional.empty(),
                h.clock.instant());
    }

    private GatewayNotification refundConfirmed(String id, String refundReference, long amountMinor) {
        return new GatewayNotification(STRIPE, id, "ch_1", NotificationType.REFUND_CONFIRMED,
                Optional.of(Money.of(amountMinor, Currency.EUR)), Optional.of(refundReference), h.clock.instant());
    }

    @Test
    @DisplayName("una notificación duplicada se reconoce y no se aplica dos veces")
    void duplicateNotification() {
        PaymentId id = authorizedOnStripe();
        GatewayNotification captured = notification("evt_1", NotificationType.CAPTURE_CONFIRMED);

        assertThat(h.notifications.handle(captured)).isEqualTo(NotificationResult.APPLIED);
        assertThat(h.notifications.handle(captured)).isEqualTo(NotificationResult.DUPLICATE);

        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.events.published(PaymentCaptured.class)).hasSize(1);
    }

    @Test
    @DisplayName("una confirmación de algo que ya está hecho da ALREADY_IN_STATE")
    void alreadyInState() {
        authorizedOnStripe();
        h.notifications.handle(notification("evt_1", NotificationType.CAPTURE_CONFIRMED));

        assertThat(h.notifications.handle(notification("evt_2", NotificationType.CAPTURE_CONFIRMED)))
                .isEqualTo(NotificationResult.ALREADY_IN_STATE);
        assertThat(h.notifications.handle(notification("evt_3", NotificationType.AUTHORIZATION_REVERSED)))
                .isEqualTo(NotificationResult.ALREADY_IN_STATE);
    }

    @Test
    @DisplayName("reembolso por la API y después su REFUND_CONFIRMED: ALREADY_IN_STATE y el total no cambia")
    void refundConfirmationOfOurOwnRefundIsNotCountedTwice() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY));
        h.operations.refund(id, Money.of(3_000, Currency.EUR));

        NotificationResult result = h.notifications.handle(refundConfirmed("evt_r1", "re_1", 3_000));

        assertThat(result).isEqualTo(NotificationResult.ALREADY_IN_STATE);
        var stored = h.payments.stored(id);
        assertThat(stored.refundedAmount()).isEqualTo(Money.of(3_000, Currency.EUR));
        assertThat(stored.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(stored.refunds()).extracting(Refund::gatewayRefundReference).containsExactly("re_1");
        assertThat(h.events.published(PaymentRefunded.class)).singleElement()
                .satisfies(e -> assertThat(e.gatewayRefundReference()).isEqualTo("re_1"));
    }

    @Test
    @DisplayName("REFUND_CONFIRMED con una referencia nueva (reembolso desde la PSP) se aplica")
    void refundConfirmationWithNewReferenceIsApplied() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY));
        h.operations.refund(id, Money.of(3_000, Currency.EUR));

        NotificationResult result = h.notifications.handle(refundConfirmed("evt_r2", "re_dashboard", 7_000));

        assertThat(result).isEqualTo(NotificationResult.APPLIED);
        var stored = h.payments.stored(id);
        assertThat(stored.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stored.refundedAmount()).isEqualTo(Money.of(10_000, Currency.EUR));
        assertThat(stored.refunds()).extracting(Refund::gatewayRefundReference)
                .containsExactly("re_1", "re_dashboard");
        assertThat(h.queryView(id).refunds()).hasSize(2);
    }

    @Test
    @DisplayName("REFUND_CONFIRMED sin referencia de reembolso no se puede construir")
    void refundConfirmationRequiresReference() {
        assertThatThrownBy(() -> new GatewayNotification(STRIPE, "evt_1", "ch_1", NotificationType.REFUND_CONFIRMED,
                Optional.of(Money.of(1, Currency.EUR)), Optional.empty(), h.clock.instant()))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("una referencia desconocida da UNKNOWN_PAYMENT y la marca se queda")
    void unknownPayment() {
        GatewayNotification unknown = new GatewayNotification(STRIPE, "evt_1", "ch_nope",
                NotificationType.CAPTURE_CONFIRMED, Optional.empty(), Optional.empty(), h.clock.instant());

        assertThat(h.notifications.handle(unknown)).isEqualTo(NotificationResult.UNKNOWN_PAYMENT);
        assertThat(h.notifications.handle(unknown)).isEqualTo(NotificationResult.DUPLICATE);
    }
}
