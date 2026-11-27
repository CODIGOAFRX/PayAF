package dev.pedrogomez.payaf.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Un APPROVED de la PSP nunca se pierde por un CONCURRENT_MODIFICATION: si otra petición guarda
 * el pago mientras se habla con la PSP, el servicio recarga y vuelve a aplicar la operación.
 */
class ApprovedOperationReconciliationTest {

    private static final Duration LATENCY = Duration.ofMillis(10);

    private final Harness h = new Harness(CircuitBreakerConfig.of(Harness.FAILURE_THRESHOLD, Harness.OPEN_DURATION),
            false);
    private final FunctionalGateway psp = new FunctionalGateway(Harness.STRIPE, h.transactions,
            auth -> GatewayResponse.approved("ch_1", LATENCY));

    ApprovedOperationReconciliationTest() {
        h.catalog.register(Harness.profile(Harness.STRIPE, 140), psp);
    }

    @AfterEach
    void discipline() {
        h.assertTransactionDiscipline();
    }

    private PaymentId authorized() {
        return h.authorize("k1").payment().id();
    }

    private PaymentId captured() {
        PaymentId id = authorized();
        psp.onOperation(op -> GatewayResponse.approved("ch_1", LATENCY));
        h.operations.capture(id);
        return id;
    }

    /** Otra petición modifica y guarda el pago (en su propia transacción) mientras la PSP responde. */
    private void concurrently(PaymentId id, Consumer<Payment> change) {
        h.transactions.inTransaction(() -> {
            Payment other = h.payments.findById(id).orElseThrow();
            change.accept(other);
            h.payments.save(other);
            return null;
        });
    }

    private static Money eur(long minor) {
        return Money.of(minor, Currency.EUR);
    }

    @Test
    @DisplayName("refund: un CME tras el APPROVED se reintenta y lo reembolsado cuadra con la PSP")
    void refundRetriesAndAddsUp() {
        PaymentId id = captured();
        psp.onOperation(op -> {
            concurrently(id, p -> p.refund(eur(2_000), "re_other", h.clock.instant()));
            return GatewayResponse.approved("re_mine", LATENCY);
        });

        PaymentView view = h.operations.refund(id, eur(3_000));

        assertThat(view.refunded()).isEqualTo(eur(5_000));
        Payment stored = h.payments.stored(id);
        assertThat(stored.refundedAmount()).isEqualTo(eur(5_000));
        assertThat(stored.refunds()).extracting(r -> r.gatewayRefundReference())
                .containsExactlyInAnyOrder("re_other", "re_mine");
        assertThat(h.events.published(PaymentRefunded.class)).hasSize(1);
    }

    @Test
    @DisplayName("refund: si la otra petición ya apuntó ese mismo reembolso, no se cuenta dos veces")
    void refundAlreadyRecordedByOther() {
        PaymentId id = captured();
        psp.onOperation(op -> {
            // P. ej. la notificación de la PSP llega y se aplica antes que nuestra respuesta.
            concurrently(id, p -> p.refund(eur(3_000), "re_mine", h.clock.instant()));
            return GatewayResponse.approved("re_mine", LATENCY);
        });

        PaymentView view = h.operations.refund(id, eur(3_000));

        assertThat(view.refunded()).isEqualTo(eur(3_000));
        assertThat(h.payments.stored(id).refundedAmount()).isEqualTo(eur(3_000));
    }

    @Test
    @DisplayName("capture: si la otra petición ya lo capturó, se devuelve CAPTURED sin error")
    void captureAlreadyCaptured() {
        PaymentId id = authorized();
        psp.onOperation(op -> {
            concurrently(id, p -> p.capture(h.clock.instant()));
            return GatewayResponse.approved("ch_1", LATENCY);
        });

        PaymentView view = h.operations.capture(id);

        assertThat(view.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("cancel: si la otra petición ya lo anuló, se devuelve CANCELED sin error")
    void cancelAlreadyCanceled() {
        PaymentId id = authorized();
        psp.onOperation(op -> {
            concurrently(id, p -> p.cancel(h.clock.instant()));
            return GatewayResponse.approved("ch_1", LATENCY);
        });

        assertThat(h.operations.cancel(id).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("capture: si la otra petición lo anuló, sale el rechazo del dominio")
    void captureAfterConcurrentCancel() {
        PaymentId id = authorized();
        psp.onOperation(op -> {
            concurrently(id, p -> p.cancel(h.clock.instant()));
            return GatewayResponse.approved("ch_1", LATENCY);
        });

        assertThatThrownBy(() -> h.operations.capture(id)).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("si el save choca siempre, tras 5 intentos OPERATION_UNRECORDED con la referencia de la PSP")
    void exhaustedAttempts() {
        PaymentId id = captured();
        AtomicInteger saves = new AtomicInteger();
        PaymentRepository alwaysConflicting = new PaymentRepository() {
            @Override
            public void save(Payment payment) {
                saves.incrementAndGet();
                throw new ConcurrentPaymentModificationException(payment.id());
            }

            @Override
            public Optional<Payment> findById(PaymentId paymentId) {
                return h.payments.findById(paymentId);
            }

            @Override
            public Optional<Payment> findByGatewayReference(GatewayId gateway, String reference) {
                return h.payments.findByGatewayReference(gateway, reference);
            }

            @Override
            public List<Payment> findRecent(int limit) {
                return h.payments.findRecent(limit);
            }

            @Override
            public List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
                return h.payments.findPendingOlderThan(cutoff, limit);
            }
        };
        PaymentOperationsService operations = new PaymentOperationsService(alwaysConflicting, h.catalog, h.breakers,
                h.events, h.transactions, h.clock);
        psp.onOperation(op -> GatewayResponse.approved("re_lost", LATENCY));

        assertThatThrownBy(() -> operations.refund(id, eur(1_000)))
                .isInstanceOfSatisfying(ApplicationException.class, e -> {
                    assertThat(e.code()).isEqualTo(ApplicationException.OPERATION_UNRECORDED);
                    assertThat(e.getMessage()).contains("re_lost").contains(id.toString());
                });
        assertThat(saves).hasValue(PaymentOperationsService.MAX_RECORD_ATTEMPTS);
        assertThat(psp.operationCalls()).isEqualTo(2);
    }
}
