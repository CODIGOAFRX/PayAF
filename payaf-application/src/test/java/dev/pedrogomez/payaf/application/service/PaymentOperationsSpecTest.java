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

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.fake.ScriptedGateway;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.Refund;
import dev.pedrogomez.payaf.domain.payment.RefundExceedsCapturedException;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.CurrencyMismatchException;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Especificación de captura, reembolso y anulación ({@link PaymentOperationsService}). */
class PaymentOperationsSpecTest {

    private final Harness h = new Harness();

    @AfterEach
    void transactionDisciplineOnEveryPath() {
        h.assertTransactionDiscipline();
    }

    private PaymentId authorizedOnStripe(String key) {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_" + key, LATENCY));
        return h.authorize(key).payment().id();
    }

    private PaymentId authorizedOnStripe() {
        return authorizedOnStripe("k1");
    }

    private PaymentId capturedOnStripe() {
        PaymentId id = authorizedOnStripe();
        h.stripe.on(CAPTURE, GatewayResponse.approved("ch_k1", LATENCY));
        h.operations.capture(id);
        return id;
    }

    private long pspOperationCalls() {
        return Stream.of(h.stripe, h.adyen)
                .mapToLong(g -> g.calls(CAPTURE) + g.calls(REFUND) + g.calls(CANCEL))
                .sum();
    }

    private static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOfSatisfying(ApplicationException.class, a -> assertThat(a.code()).isEqualTo(code));
    }

    // ---------------------------------------------------------------------------------
    // PAYMENT_NOT_FOUND
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("capturar, reembolsar o anular un pago que no existe da PAYMENT_NOT_FOUND sin llamar a ninguna PSP")
    void notFoundForEveryOperation() {
        PaymentId ghost = PaymentId.of(new UUID(4, 4));

        assertThatThrownBy(() -> h.operations.capture(ghost))
                .satisfies(e -> assertCode(e, ApplicationException.PAYMENT_NOT_FOUND));
        assertThatThrownBy(() -> h.operations.refund(ghost, Money.of(1, Currency.EUR)))
                .satisfies(e -> assertCode(e, ApplicationException.PAYMENT_NOT_FOUND));
        assertThatThrownBy(() -> h.operations.cancel(ghost))
                .satisfies(e -> assertCode(e, ApplicationException.PAYMENT_NOT_FOUND));
        assertThat(pspOperationCalls()).isZero();
        assertThat(h.breakers.saves()).isZero();
    }

    @Test
    @DisplayName("argumentos nulos: NullPointerException")
    void nullArguments() {
        PaymentId id = authorizedOnStripe();
        assertThatThrownBy(() -> h.operations.capture(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> h.operations.cancel(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> h.operations.refund(null, Money.of(1, Currency.EUR)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> h.operations.refund(id, null)).isInstanceOf(NullPointerException.class);
        assertThat(pspOperationCalls()).isZero();
    }

    // ---------------------------------------------------------------------------------
    // El dominio rechaza: sale la DomainException y no se llama a la PSP
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("capturar un pago FAILED: IllegalPaymentTransitionException y ninguna PSP llamada")
    void captureFailedPayment() {
        h.stripe.on(AUTHORIZE, GatewayResponse.declined("insufficient_funds", LATENCY));
        PaymentId id = h.authorize("k1").payment().id();
        PaymentView before = h.queryView(id);

        assertThatThrownBy(() -> h.operations.capture(id)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> h.operations.cancel(id)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(1, Currency.EUR)))
                .isInstanceOf(IllegalPaymentTransitionException.class);

        assertThat(pspOperationCalls()).isZero();
        assertThat(h.queryView(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("anular un pago capturado, capturar dos veces y reembolsar uno autorizado: DomainException sin PSP")
    void otherIllegalTransitions() {
        PaymentId id = capturedOnStripe();
        long callsAfterCapture = pspOperationCalls();

        assertThatThrownBy(() -> h.operations.cancel(id)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> h.operations.capture(id)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(pspOperationCalls()).isEqualTo(callsAfterCapture);

        PaymentId authorized = authorizedOnStripe("k2");
        assertThatThrownBy(() -> h.operations.refund(authorized, Money.of(100, Currency.EUR)))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(pspOperationCalls()).isEqualTo(callsAfterCapture);
    }

    @Test
    @DisplayName("reembolsos con importe 0, negativo o en otra divisa: DomainException sin llamar a la PSP ni cambiar el pago")
    void invalidRefundAmounts() {
        PaymentId id = capturedOnStripe();
        PaymentView before = h.queryView(id);
        long calls = pspOperationCalls();

        assertThatThrownBy(() -> h.operations.refund(id, Money.of(0, Currency.EUR)))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(-500, Currency.EUR)))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(Long.MIN_VALUE, Currency.EUR)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(500, Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(Long.MAX_VALUE, Currency.EUR)))
                .isInstanceOf(RefundExceedsCapturedException.class);

        assertThat(pspOperationCalls()).isEqualTo(calls);
        assertThat(h.queryView(id)).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------
    // Siempre la pasarela authorizedBy
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("captura, reembolso y anulación van a la pasarela que autorizó (Adyen), aunque Stripe sea más barata y su breaker esté abierto")
    void alwaysTheAuthorizingGateway() {
        h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
        h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_1", LATENCY));
        PaymentId captureMe = h.authorize("k1").payment().id();
        h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
        h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_2", LATENCY));
        PaymentId cancelMe = h.authorize("k2").payment().id();
        assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.OPEN);

        // Se abre también el de Adyen: la operación se pide igualmente (no hay otra a la que ir).
        var adyenBreaker = h.breakers.peek(ADYEN);
        synchronized (adyenBreaker) {
            for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
                adyenBreaker.recordFailure(h.clock.instant());
            }
        }
        assertThat(adyenBreaker.state()).isEqualTo(CircuitState.OPEN);

        h.adyen.on(CAPTURE, GatewayResponse.approved("psp_1", LATENCY));
        h.adyen.on(REFUND, GatewayResponse.approved("re_adyen", LATENCY));
        h.adyen.on(CANCEL, GatewayResponse.approved("psp_2", LATENCY));

        assertThat(h.operations.capture(captureMe).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.operations.refund(captureMe, Money.of(1_000, Currency.EUR)).status())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(h.operations.cancel(cancelMe).status()).isEqualTo(PaymentStatus.CANCELED);

        assertThat(h.stripe.calls(CAPTURE) + h.stripe.calls(REFUND) + h.stripe.calls(CANCEL)).isZero();
        assertThat(h.adyen.calls()).extracting(ScriptedGateway.Call::detail)
                .contains("psp_1 " + Money.of(10_000, Currency.EUR), "psp_1 " + Money.of(1_000, Currency.EUR), "psp_2");
        assertThat(h.events.published(PaymentCanceled.class)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------
    // La PSP no aprueba: GATEWAY_OPERATION_FAILED y el pago idéntico
    // ---------------------------------------------------------------------------------

    static Stream<Arguments> nonApprovals() {
        return Stream.of(
                Arguments.of("ERROR", GatewayResponse.error("http_502", LATENCY)),
                Arguments.of("TIMEOUT", GatewayResponse.timeout(LATENCY)),
                Arguments.of("DECLINED", GatewayResponse.declined("refused", LATENCY)));
    }

    @ParameterizedTest(name = "captura con {0}")
    @MethodSource("nonApprovals")
    @DisplayName("captura no aprobada: GATEWAY_OPERATION_FAILED y la PaymentView no cambia en nada")
    void captureNotApproved(String label, GatewayResponse response) {
        PaymentId id = authorizedOnStripe();
        PaymentView before = h.queryView(id);
        h.stripe.on(CAPTURE, response);

        assertThatThrownBy(() -> h.operations.capture(id))
                .satisfies(e -> assertCode(e, ApplicationException.GATEWAY_OPERATION_FAILED));

        assertThat(h.queryView(id)).isEqualTo(before);
        assertThat(h.adyen.calls()).isEmpty();
    }

    @ParameterizedTest(name = "reembolso con {0}")
    @MethodSource("nonApprovals")
    @DisplayName("reembolso no aprobado: GATEWAY_OPERATION_FAILED, la PaymentView no cambia y no hay PaymentRefunded")
    void refundNotApproved(String label, GatewayResponse response) {
        PaymentId id = capturedOnStripe();
        PaymentView before = h.queryView(id);
        h.stripe.on(REFUND, response);

        assertThatThrownBy(() -> h.operations.refund(id, Money.of(2_000, Currency.EUR)))
                .satisfies(e -> assertCode(e, ApplicationException.GATEWAY_OPERATION_FAILED));

        assertThat(h.queryView(id)).isEqualTo(before);
        assertThat(h.events.published(PaymentRefunded.class)).isEmpty();
    }

    @ParameterizedTest(name = "anulación con {0}")
    @MethodSource("nonApprovals")
    @DisplayName("anulación no aprobada: GATEWAY_OPERATION_FAILED y la PaymentView no cambia")
    void cancelNotApproved(String label, GatewayResponse response) {
        PaymentId id = authorizedOnStripe();
        PaymentView before = h.queryView(id);
        h.stripe.on(CANCEL, response);

        assertThatThrownBy(() -> h.operations.cancel(id))
                .satisfies(e -> assertCode(e, ApplicationException.GATEWAY_OPERATION_FAILED));

        assertThat(h.queryView(id)).isEqualTo(before);
        assertThat(h.events.published(PaymentCanceled.class)).isEmpty();
    }

    @Test
    @DisplayName("si el adaptador lanza en una captura: GATEWAY_OPERATION_FAILED y el pago no cambia")
    void adapterExceptionDuringCapture() {
        Harness other = new Harness(CircuitBreakerConfig.of(2, Harness.OPEN_DURATION), false);
        FunctionalGateway broken = new FunctionalGateway(STRIPE, other.transactions,
                a -> GatewayResponse.approved("ch_x", LATENCY))
                .onOperation(op -> {
                    throw new IllegalStateException("boom");
                });
        other.catalog.register(Harness.profile(STRIPE, 140), broken);
        PaymentId id = other.authorize("k1").payment().id();
        PaymentView before = other.queryView(id);

        assertThatThrownBy(() -> other.operations.capture(id))
                .satisfies(e -> assertCode(e, ApplicationException.GATEWAY_OPERATION_FAILED));

        assertThat(other.queryView(id)).isEqualTo(before);
        assertThat(broken.operationCalls()).isEqualTo(1);
        assertThat(other.breakers.peek(STRIPE).snapshot().consecutiveFailures()).isEqualTo(1);
        other.assertTransactionDiscipline();
    }

    // ---------------------------------------------------------------------------------
    // Reembolsos
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("la referencia de reembolso que devuelve la PSP es la que queda en refunds")
    void refundKeepsPspReference() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_psp_777", LATENCY));

        PaymentView view = h.operations.refund(id, Money.of(1_234, Currency.EUR));

        assertThat(view.refunds()).extracting(Refund::gatewayRefundReference).containsExactly("re_psp_777");
        assertThat(view.refunds()).extracting(Refund::amount).containsExactly(Money.of(1_234, Currency.EUR));
        assertThat(h.payments.stored(id).refunds()).extracting(Refund::gatewayRefundReference)
                .containsExactly("re_psp_777");
        assertThat(view.refunds()).noneMatch(r -> r.gatewayRefundReference().startsWith("dry-run"));
        assertThat(h.events.published(PaymentRefunded.class)).singleElement()
                .satisfies(e -> assertThat(e.gatewayRefundReference()).isEqualTo("re_psp_777"));
    }

    @Test
    @DisplayName("varios reembolsos parciales suman exactamente y el último deja el pago REFUNDED")
    void partialRefundsAddUpExactly() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND,
                GatewayResponse.approved("re_1", LATENCY),
                GatewayResponse.approved("re_2", LATENCY),
                GatewayResponse.approved("re_3", LATENCY));

        PaymentView first = h.operations.refund(id, Money.of(1, Currency.EUR));
        PaymentView second = h.operations.refund(id, Money.of(3_333, Currency.EUR));
        PaymentView third = h.operations.refund(id, Money.of(6_666, Currency.EUR));

        assertThat(first.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(first.remainingRefundable()).isEqualTo(Money.of(9_999, Currency.EUR));
        assertThat(second.refunded()).isEqualTo(Money.of(3_334, Currency.EUR));
        assertThat(third.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(third.refunded()).isEqualTo(Money.of(10_000, Currency.EUR));
        assertThat(third.remainingRefundable()).isEqualTo(Money.of(0, Currency.EUR));
        assertThat(third.refunds()).extracting(Refund::gatewayRefundReference).containsExactly("re_1", "re_2", "re_3");
        assertThat(third).isEqualTo(h.queryView(id));
    }

    @Test
    @DisplayName("pasarse de lo que queda da RefundExceedsCaptured sin llamar a la PSP; lo que queda exacto sí se puede")
    void refundExceedingRemaining() {
        PaymentId id = capturedOnStripe();
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY), GatewayResponse.approved("re_2", LATENCY));
        h.operations.refund(id, Money.of(6_000, Currency.EUR));
        PaymentView before = h.queryView(id);

        assertThatThrownBy(() -> h.operations.refund(id, Money.of(4_001, Currency.EUR)))
                .isInstanceOf(RefundExceedsCapturedException.class);
        assertThat(h.stripe.calls(REFUND)).isEqualTo(1);
        assertThat(h.queryView(id)).isEqualTo(before);

        assertThat(h.operations.refund(id, Money.of(4_000, Currency.EUR)).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(h.stripe.calls(REFUND)).isEqualTo(2);

        // Ya todo devuelto: un céntimo más es una transición ilegal desde REFUNDED, sin PSP.
        assertThatThrownBy(() -> h.operations.refund(id, Money.of(1, Currency.EUR)))
                .isInstanceOf(DomainException.class);
        assertThat(h.stripe.calls(REFUND)).isEqualTo(2);
    }
}
