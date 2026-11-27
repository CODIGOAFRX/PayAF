package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.CAPTURE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.REFUND;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.RefundExceedsCapturedException;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentOperationsServiceTest {

    private final Harness h = new Harness();

    @AfterEach
    void noGatewayCallInsideATransaction() {
        h.assertTransactionDiscipline();
    }

    private PaymentId authorizedOnStripe() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
        return h.authorize("k1").payment().id();
    }

    @Test
    @DisplayName("una captura aprobada por la PSP deja el pago CAPTURED y publica el evento")
    void captureApproved() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CAPTURE, GatewayResponse.approved("ch_1", LATENCY));

        PaymentView view = h.operations.capture(id);

        assertThat(view.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.stripe.calls(CAPTURE)).isEqualTo(1);
        assertThat(h.events.published(PaymentCaptured.class)).hasSize(1);
    }

    @Test
    @DisplayName("una captura que falla en la PSP no cambia el estado del pago")
    void captureFailingAtGatewayKeepsState() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CAPTURE, GatewayResponse.error("http_502", LATENCY));

        assertThatThrownBy(() -> h.operations.capture(id))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.GATEWAY_OPERATION_FAILED));

        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(h.events.published(PaymentCaptured.class)).isEmpty();
        // Sin failover: Adyen no tiene esa autorización.
        assertThat(h.adyen.calls()).isEmpty();
        assertThat(h.breakers.get(Harness.STRIPE).snapshot().consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("si el dominio no permite la operación, no se llama a la PSP")
    void illegalOperationDoesNotReachGateway() {
        PaymentId id = authorizedOnStripe();

        assertThatThrownBy(() -> h.operations.refund(id, Money.of(100, Currency.EUR)))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(h.stripe.calls(REFUND)).isZero();
    }

    @Test
    @DisplayName("un reembolso mayor de lo capturado se rechaza antes de llamar a la PSP")
    void excessiveRefundDoesNotReachGateway() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CAPTURE, GatewayResponse.approved("ch_1", LATENCY));
        h.operations.capture(id);

        assertThatThrownBy(() -> h.operations.refund(id, Money.of(10_001, Currency.EUR)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        assertThat(h.stripe.calls(REFUND)).isZero();
    }

    @Test
    @DisplayName("un pago que no existe da PAYMENT_NOT_FOUND")
    void unknownPayment() {
        assertThatThrownBy(() -> h.operations.cancel(PaymentId.of(new UUID(7, 7))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.PAYMENT_NOT_FOUND));
    }
}
