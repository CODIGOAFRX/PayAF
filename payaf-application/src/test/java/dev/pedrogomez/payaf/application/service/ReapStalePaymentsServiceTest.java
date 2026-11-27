package dev.pedrogomez.payaf.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReapStalePaymentsServiceTest {

    private static final Duration OLDER_THAN = Duration.ofMinutes(2);

    private final Harness h = new Harness();
    private final ReapStalePaymentsService reaper = new ReapStalePaymentsService(h.payments, h.idempotency,
            h.events, h.transactions, h.clock);

    @AfterEach
    void discipline() {
        h.assertTransactionDiscipline();
    }

    /** Deja un pago PENDING con su clave reservada, como lo deja la transacción 1. */
    private PaymentId pending(String key, GatewayAttempt... attempts) {
        PaymentRequest request = Harness.request(key, 10_000);
        PaymentId id = PaymentId.of(UUID.randomUUID());
        Instant now = h.clock.instant();
        h.transactions.inTransaction(() -> {
            h.idempotency.tryReserve(request.key(), request.fingerprint(), id, now);
            Payment payment = Payment.create(id, request, now);
            for (GatewayAttempt attempt : attempts) {
                payment.recordAttempt(attempt);
            }
            h.payments.save(payment);
            return null;
        });
        return id;
    }

    private GatewayAttempt attempt(GatewayId gateway, AttemptOutcome outcome, String reference) {
        return new GatewayAttempt(gateway, outcome, Duration.ofMillis(50), Optional.empty(),
                Optional.ofNullable(reference), h.clock.instant());
    }

    @Test
    @DisplayName("un PENDING viejo sin aprobación pasa a FAILED ABANDONED y su clave se completa")
    void abandonsStalePendingWithoutApproval() {
        PaymentId id = pending("k1", attempt(Harness.STRIPE, AttemptOutcome.TIMEOUT, null));
        h.clock.advance(Duration.ofMinutes(3));

        ReapReport report = reaper.reap(OLDER_THAN);

        assertThat(report).isEqualTo(new ReapReport(1, 0));
        Payment stored = h.payments.stored(id);
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.failureReason()).contains(ReapStalePaymentsService.ABANDONED);
        assertThat(h.idempotency.isCompleted(stored.request().key())).isTrue();
        assertThat(h.events.published(PaymentFailed.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(ReapStalePaymentsService.ABANDONED));
    }

    @Test
    @DisplayName("un PENDING viejo con un intento APPROVED se autoriza con esa pasarela y referencia")
    void recoversApprovedAuthorization() {
        PaymentId id = pending("k1",
                attempt(Harness.STRIPE, AttemptOutcome.ERROR, null),
                attempt(Harness.ADYEN, AttemptOutcome.APPROVED, "psp-123"));
        h.clock.advance(Duration.ofMinutes(3));

        ReapReport report = reaper.reap(OLDER_THAN);

        assertThat(report).isEqualTo(new ReapReport(0, 1));
        Payment stored = h.payments.stored(id);
        assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(stored.authorizedBy()).contains(Harness.ADYEN);
        assertThat(stored.gatewayReference()).contains("psp-123");
        assertThat(h.idempotency.isCompleted(stored.request().key())).isTrue();
        assertThat(h.events.published(PaymentAuthorized.class)).hasSize(1);
        // El reaper no llama a ninguna pasarela.
        assertThat(h.stripe.calls()).isEmpty();
        assertThat(h.adyen.calls()).isEmpty();
    }

    @Test
    @DisplayName("un PENDING reciente o un pago ya cerrado no se tocan")
    void ignoresYoungAndClosedPayments() {
        PaymentId young = pending("joven");
        h.clock.advance(Duration.ofMinutes(1));

        assertThat(reaper.reap(OLDER_THAN)).isEqualTo(new ReapReport(0, 0));
        assertThat(h.payments.stored(young).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(h.idempotency.isCompleted(Harness.request("joven", 10_000).key())).isFalse();
    }

    @Test
    @DisplayName("una repetición del comercio tras el reaper recibe el pago cerrado, no IDEMPOTENCY_IN_PROGRESS")
    void replayAfterReapReturnsClosedPayment() {
        PaymentId id = pending("k1");
        h.clock.advance(Duration.ofMinutes(3));
        reaper.reap(OLDER_THAN);

        var replay = h.authorize(Harness.request("k1", 10_000));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().id()).isEqualTo(id);
        assertThat(replay.payment().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("un CONCURRENT_MODIFICATION en un pago lo salta y el reaper sigue con el resto")
    void concurrentModificationSkipsOnlyThatPayment() throws Exception {
        PaymentId contended = pending("k1");
        h.clock.advance(Duration.ofSeconds(1));
        PaymentId other = pending("k2");
        h.clock.advance(Duration.ofMinutes(3));

        // Repositorio que, justo después de que el reaper cargue «contended», deja que otro
        // proceso (otro hilo, con su propia transacción) lo autorice y lo guarde.
        ExecutorService otherProcess = Executors.newSingleThreadExecutor();
        PaymentRepository racing = new DelegatingRepository(h.payments) {
            @Override
            public Optional<Payment> findById(PaymentId id) {
                Optional<Payment> loaded = super.findById(id);
                if (id.equals(contended)) {
                    try {
                        otherProcess.submit(() -> h.transactions.inTransaction(() -> {
                            Payment p = h.payments.findById(id).orElseThrow();
                            p.authorize(Harness.STRIPE, "ch_late", h.clock.instant());
                            h.payments.save(p);
                            return null;
                        })).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return loaded;
            }
        };
        ReapStalePaymentsService racingReaper = new ReapStalePaymentsService(racing, h.idempotency, h.events,
                h.transactions, h.clock);

        ReapReport report;
        try {
            report = racingReaper.reap(OLDER_THAN);
        } finally {
            otherProcess.shutdownNow();
        }

        assertThat(report).isEqualTo(new ReapReport(1, 0));
        assertThat(h.payments.stored(contended).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(h.payments.stored(contended).gatewayReference()).contains("ch_late");
        assertThat(h.payments.stored(other).status()).isEqualTo(PaymentStatus.FAILED);
        // La transacción del pago disputado se ha deshecho: ni eventos ni clave completada.
        assertThat(h.events.published(PaymentFailed.class)).singleElement()
                .satisfies(e -> assertThat(e.paymentId()).isEqualTo(other));
        assertThat(h.idempotency.isCompleted(Harness.request("k1", 10_000).key())).isFalse();
    }

    @Test
    @DisplayName("si el reaper cierra el pago mientras la PSP responde, la autorización propaga CONCURRENT_MODIFICATION")
    void authorizationLosesAgainstReaper() {
        Harness slow = new Harness(CircuitBreakerConfig.of(Harness.FAILURE_THRESHOLD, Harness.OPEN_DURATION), false);
        ReapStalePaymentsService slowReaper = new ReapStalePaymentsService(slow.payments, slow.idempotency,
                slow.events, slow.transactions, slow.clock);
        // La PSP tarda tanto que, mientras responde, el reaper da el pago por abandonado.
        FunctionalGateway psp = new FunctionalGateway(Harness.STRIPE, slow.transactions, auth -> {
            slow.clock.advance(Duration.ofMinutes(3));
            assertThat(slowReaper.reap(OLDER_THAN)).isEqualTo(new ReapReport(1, 0));
            return GatewayResponse.approved("ch_1", Duration.ofMinutes(3));
        });
        slow.catalog.register(Harness.profile(Harness.STRIPE, 140), psp);
        PaymentRequest request = Harness.request("k1", 10_000);

        assertThatThrownBy(() -> slow.authorizeService.authorize(new AuthorizePaymentCommand(request)))
                .isInstanceOfSatisfying(ConcurrentPaymentModificationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.CONCURRENT_MODIFICATION));

        Payment stored = slow.transactions.inTransaction(() -> slow.payments.findRecent(1)).getFirst();
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.failureReason()).contains(ReapStalePaymentsService.ABANDONED);
        assertThat(slow.idempotency.isCompleted(request.key())).isTrue();
        slow.assertTransactionDiscipline();
    }

    @Test
    @DisplayName("el límite del lote deja el resto para la siguiente pasada")
    void batchSizeLimitsOnePass() {
        ReapStalePaymentsService small = new ReapStalePaymentsService(h.payments, h.idempotency, h.events,
                h.transactions, h.clock, 2);
        for (int i = 0; i < 3; i++) {
            pending("k" + i);
            h.clock.advance(Duration.ofSeconds(1));
        }
        h.clock.advance(Duration.ofMinutes(3));

        assertThat(small.reap(OLDER_THAN)).isEqualTo(new ReapReport(2, 0));
        assertThat(small.reap(OLDER_THAN)).isEqualTo(new ReapReport(1, 0));
        assertThat(small.reap(OLDER_THAN)).isEqualTo(new ReapReport(0, 0));
    }

    @Test
    @DisplayName("rechaza una antigüedad nula, cero o negativa")
    void rejectsInvalidAge() {
        assertThatThrownBy(() -> reaper.reap(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reaper.reap(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reaper.reap(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    /** Repositorio que delega en otro; los tests sobrescriben lo que necesiten interceptar. */
    private static class DelegatingRepository implements PaymentRepository {

        private final PaymentRepository delegate;

        DelegatingRepository(PaymentRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(Payment payment) {
            delegate.save(payment);
        }

        @Override
        public Optional<Payment> findById(PaymentId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference) {
            return delegate.findByGatewayReference(gateway, gatewayReference);
        }

        @Override
        public List<Payment> findRecent(int limit) {
            return delegate.findRecent(limit);
        }

        @Override
        public List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
            return delegate.findPendingOlderThan(cutoff, limit);
        }
    }
}
