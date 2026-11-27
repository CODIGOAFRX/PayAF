package dev.pedrogomez.payaf.adapter.out.persistencememory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.service.ReapStalePaymentsService;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tester (fase 4a): el reaper de pagos huérfanos con los adaptadores reales de memoria.
 */
class ReaperOverRealAdaptersTest {

    private static final Duration OLDER_THAN = Duration.ofMinutes(2);

    private final RealAdaptersHarness h = new RealAdaptersHarness();

    /** Deja un PENDING con su clave reservada, como lo deja la transacción 1 de la autorización. */
    private PaymentId pending(String key, Instant at, GatewayAttempt... attempts) {
        PaymentRequest request = RealAdaptersHarness.request(key, 10_000);
        PaymentId id = PaymentId.of(UUID.randomUUID());
        h.transactions.inTransaction(() -> {
            h.idempotency.tryReserve(request.key(), request.fingerprint(), id, at);
            Payment payment = Payment.create(id, request, at);
            for (GatewayAttempt attempt : attempts) {
                payment.recordAttempt(attempt);
            }
            h.payments.save(payment);
            return null;
        });
        return id;
    }

    private static GatewayAttempt attempt(AttemptOutcome outcome, String reference, Instant at) {
        return new GatewayAttempt(RealAdaptersHarness.PSP, outcome, Duration.ofMillis(5), Optional.empty(),
                Optional.ofNullable(reference), at);
    }

    @Test
    @DisplayName("PENDING viejo sin APPROVED → FAILED ABANDONED, clave completada y el reintento es un replay")
    void abandonsAndCompletesKey() {
        PaymentId id = pending("k1", RealAdaptersHarness.T0,
                attempt(AttemptOutcome.TIMEOUT, null, RealAdaptersHarness.T0));
        h.clock.advance(Duration.ofMinutes(3));

        ReapReport report = h.reaper.reap(OLDER_THAN);

        assertThat(report).isEqualTo(new ReapReport(1, 0));
        Payment stored = h.payments.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.failureReason()).contains(ReapStalePaymentsService.ABANDONED);
        assertThat(h.idempotency.isCompleted(RealAdaptersHarness.request("k1", 10_000).key())).isTrue();
        assertThat(h.eventsOf(id, PaymentFailed.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("ABANDONED"));

        // El comercio reintenta con la misma clave y el mismo cuerpo: replay, no IN_PROGRESS.
        AuthorizationResult replay = h.authorize("k1", 10_000);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().id()).isEqualTo(id);
        assertThat(replay.payment().status()).isEqualTo(PaymentStatus.FAILED);
        // Y con otro cuerpo: conflicto.
        assertThatThrownBy(() -> h.authorize("k1", 20_000)).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("PENDING viejo con un APPROVED registrado → AUTHORIZED con esa pasarela y referencia (la última)")
    void recoversApproved() {
        Instant t0 = RealAdaptersHarness.T0;
        PaymentId id = pending("k1", t0,
                attempt(AttemptOutcome.ERROR, null, t0),
                attempt(AttemptOutcome.APPROVED, "ref-old", t0),
                attempt(AttemptOutcome.TIMEOUT, null, t0),
                attempt(AttemptOutcome.APPROVED, "ref-last", t0));
        h.clock.advance(Duration.ofMinutes(3));

        ReapReport report = h.reaper.reap(OLDER_THAN);

        assertThat(report).isEqualTo(new ReapReport(0, 1));
        Payment stored = h.payments.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(stored.authorizedBy()).contains(RealAdaptersHarness.PSP);
        assertThat(stored.gatewayReference()).contains("ref-last");
        assertThat(h.eventsOf(id, PaymentAuthorized.class)).hasSize(1);
        assertThat(h.authorize("k1", 10_000).payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        // Y el pago recuperado se puede capturar con normalidad.
        assertThat(h.operations.capture(id).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("los pagos recientes no se tocan; justo en el corte (antigüedad == olderThan) tampoco")
    void recentAndBoundaryUntouched() {
        PaymentId exactlyAtCutoff = pending("edge", RealAdaptersHarness.T0);
        h.clock.advance(Duration.ofMinutes(1));
        PaymentId young = pending("young", h.clock.instant());
        h.clock.advance(Duration.ofMinutes(1)); // exactlyAtCutoff tiene ahora 2 min justos

        ReapReport report = h.reaper.reap(OLDER_THAN);

        assertThat(report.total()).isZero();
        assertThat(h.payments.findById(exactlyAtCutoff).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(h.payments.findById(young).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(h.payments.findById(exactlyAtCutoff).orElseThrow().version()).isEqualTo(1);

        h.clock.advance(Duration.ofNanos(1));
        assertThat(h.reaper.reap(OLDER_THAN)).isEqualTo(new ReapReport(1, 0));
        assertThat(h.payments.findById(young).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("no toca pagos viejos que no estén en PENDING (AUTHORIZED, FAILED, CAPTURED, CANCELED)")
    void ignoresNonPending() {
        PaymentId authorized = h.authorize("a", 10_000).payment().id();
        PaymentId captured = h.authorize("c", 10_000).payment().id();
        h.operations.capture(captured);
        PaymentId canceled = h.authorize("x", 10_000).payment().id();
        h.operations.cancel(canceled);
        h.gateway.onAuthorize = auth -> GatewayResponse.declined("nope", Duration.ofMillis(1));
        PaymentId failed = h.authorize("f", 10_000).payment().id();
        List<PaymentId> all = List.of(authorized, captured, canceled, failed);
        List<Long> versions = all.stream().map(id -> h.payments.findById(id).orElseThrow().version()).toList();
        int eventsBefore = h.allEvents().size();
        h.clock.advance(Duration.ofHours(5));

        assertThat(h.reaper.reap(OLDER_THAN).total()).isZero();

        assertThat(all.stream().map(id -> h.payments.findById(id).orElseThrow().version()).toList())
                .isEqualTo(versions);
        assertThat(h.allEvents()).hasSize(eventsBefore);
    }

    /** Repositorio real con un pago «venenoso» y otro que alguien guarda justo antes que el reaper. */
    private static final class SabotagedRepository implements PaymentRepository {

        private final InMemoryPaymentRepository real;
        private final PaymentId poison;
        private final PaymentId raced;

        SabotagedRepository(InMemoryPaymentRepository real, PaymentId poison, PaymentId raced) {
            this.real = real;
            this.poison = poison;
            this.raced = raced;
        }

        @Override
        public void save(Payment payment) {
            if (payment.id().equals(raced)) {
                // Otra petición lo guarda (sin cambios, solo sube la versión) mientras el reaper
                // lo tiene en la mano: el save del reaper parte de una versión ya vieja.
                real.save(real.findById(raced).orElseThrow());
            }
            real.save(payment);
        }

        @Override
        public Optional<Payment> findById(PaymentId id) {
            if (id.equals(poison)) {
                throw new IllegalStateException("fila corrupta");
            }
            return real.findById(id);
        }

        @Override
        public Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference) {
            return real.findByGatewayReference(gateway, gatewayReference);
        }

        @Override
        public List<Payment> findRecent(int limit) {
            return real.findRecent(limit);
        }

        @Override
        public List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
            return real.findPendingOlderThan(cutoff, limit);
        }
    }

    @Test
    @DisplayName("un pago venenoso y un CONCURRENT_MODIFICATION no frenan al resto del lote")
    void poisonAndRaceDoNotStopTheBatch() {
        Instant t0 = RealAdaptersHarness.T0;
        PaymentId first = pending("p1", t0);
        PaymentId poison = pending("poison", t0.plusSeconds(1));
        PaymentId raced = pending("raced", t0.plusSeconds(2));
        PaymentId last = pending("p4", t0.plusSeconds(3),
                attempt(AttemptOutcome.APPROVED, "ref-4", t0.plusSeconds(3)));
        h.clock.advance(Duration.ofMinutes(10));
        ReapStalePaymentsService reaper = new ReapStalePaymentsService(
                new SabotagedRepository(h.payments, poison, raced), h.idempotency, h.events, h.transactions, h.clock);

        ReapReport report = reaper.reap(OLDER_THAN);

        assertThat(report).isEqualTo(new ReapReport(1, 1));
        assertThat(h.payments.findById(first).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(h.payments.findById(last).orElseThrow().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        // El venenoso y el que perdió la carrera siguen PENDING, sin eventos ni clave completada.
        assertThat(h.payments.findById(poison).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(h.payments.findById(raced).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(h.eventsOf(raced, PaymentFailed.class)).isEmpty();
        assertThat(h.idempotency.isCompleted(RealAdaptersHarness.request("raced", 10_000).key())).isFalse();
        assertThat(h.idempotency.isCompleted(RealAdaptersHarness.request("poison", 10_000).key())).isFalse();

        // En la pasada siguiente, ya sin sabotaje, se resuelven.
        assertThat(h.reaper.reap(OLDER_THAN)).isEqualTo(new ReapReport(2, 0));
    }

    @Test
    @DisplayName("el reaper gana a una autorización en curso: la autorización da CONCURRENT_MODIFICATION y manda lo del reaper")
    void reaperWinsAgainstSlowAuthorization() throws Exception {
        CountDownLatch atGateway = new CountDownLatch(1);
        CountDownLatch reaperDone = new CountDownLatch(1);
        h.gateway.onAuthorize = auth -> {
            atGateway.countDown();
            try {
                reaperDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GatewayResponse.approved("late-ref", Duration.ofSeconds(1));
        };
        CompletableFuture<Object> auth = CompletableFuture.supplyAsync(() -> {
            try {
                return h.authorize("slow", 10_000);
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertThat(atGateway.await(10, TimeUnit.SECONDS)).isTrue();
        h.clock.advance(Duration.ofMinutes(3));

        ReapReport report = h.reaper.reap(OLDER_THAN);
        reaperDone.countDown();
        Object result = auth.get(10, TimeUnit.SECONDS);

        assertThat(report).isEqualTo(new ReapReport(1, 0));
        assertThat(result).isInstanceOf(ConcurrentPaymentModificationException.class);
        Payment stored = h.payments.findRecent(1).getFirst();
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.failureReason()).contains("ABANDONED");
        assertThat(h.eventsOf(stored.id(), PaymentFailed.class)).hasSize(1);
        assertThat(h.eventsOf(stored.id(), PaymentAuthorized.class)).isEmpty();
        assertThat(h.authorize("slow", 10_000).replayed()).isTrue();
    }

    @Test
    @DisplayName("propiedad: reaper y autorización cruzados al azar nunca dejan un estado incoherente")
    void reaperVersusAuthorizationProperty() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 60; round++) {
                RealAdaptersHarness r = new RealAdaptersHarness();
                int delayMicros = random.nextInt(3_000);
                int reaperDelayMicros = random.nextInt(3_000);
                CountDownLatch clockMoved = new CountDownLatch(1);
                r.gateway.onAuthorize = a -> {
                    // La PSP «tarda» más de 2 min de reloj y algo de tiempo real al azar.
                    r.clock.advance(Duration.ofMinutes(3));
                    clockMoved.countDown();
                    sleepMicros(delayMicros);
                    return GatewayResponse.approved("ref", Duration.ofMillis(1));
                };
                String key = "k" + round;
                Future<Object> auth = pool.submit(() -> {
                    try {
                        return r.authorize(key, 10_000);
                    } catch (RuntimeException e) {
                        return e;
                    }
                });
                Future<ReapReport> reap = pool.submit(() -> {
                    clockMoved.await(10, TimeUnit.SECONDS);
                    sleepMicros(reaperDelayMicros);
                    return r.reaper.reap(OLDER_THAN);
                });
                Object authResult = auth.get(10, TimeUnit.SECONDS);
                ReapReport report = reap.get(10, TimeUnit.SECONDS);
                String context = "semilla " + seed + ", ronda " + round;

                Payment stored = r.payments.findRecent(1).getFirst();
                assertThat(Set.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED)).as(context)
                        .contains(stored.status());
                assertThat(r.idempotency.isCompleted(RealAdaptersHarness.request(key, 10_000).key()))
                        .as(context).isTrue();
                int terminalEvents = r.eventsOf(stored.id(), PaymentAuthorized.class).size()
                        + r.eventsOf(stored.id(), PaymentFailed.class).size();
                assertThat(terminalEvents).as(context).isEqualTo(1);
                if (authResult instanceof AuthorizationResult ok) {
                    assertThat(ok.payment().status()).as(context).isEqualTo(stored.status());
                    assertThat(stored.status()).as(context).isEqualTo(PaymentStatus.AUTHORIZED);
                    assertThat(report.total()).as(context).isZero();
                } else {
                    assertThat(authResult).as(context).isInstanceOf(ConcurrentPaymentModificationException.class);
                    assertThat(stored.status()).as(context).isEqualTo(PaymentStatus.FAILED);
                    assertThat(stored.failureReason()).as(context).contains("ABANDONED");
                    assertThat(report).as(context).isEqualTo(new ReapReport(1, 0));
                }
                // Y el comercio, al reintentar, recibe exactamente lo guardado.
                AuthorizationResult replay = r.authorize(key, 10_000);
                assertThat(replay.replayed()).as(context).isTrue();
                assertThat(replay.payment().status()).as(context).isEqualTo(stored.status());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleepMicros(int micros) {
        try {
            TimeUnit.MICROSECONDS.sleep(micros);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
