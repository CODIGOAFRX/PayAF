package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore.Reservation;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Los adaptadores de PostgreSQL contra un PostgreSQL 17 de verdad (Testcontainers). */
@SpringBootTest(classes = JpaTestApplication.class)
@Import(JpaTestApplication.Postgres.class)
class PostgresAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    private static final GatewayId STRIPE = GatewayId.of("stripe");

    @Autowired
    private JpaPaymentRepository payments;
    @Autowired
    private JdbcIdempotencyStore idempotency;
    @Autowired
    private JdbcProcessedNotificationStore processed;
    @Autowired
    private OutboxEventPublisher publisher;
    @Autowired
    private JdbcOutboxReader outbox;
    @Autowired
    private SpringTransactionRunner tx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;

    @BeforeEach
    void cleanOutbox() {
        jdbc.update("DELETE FROM outbox");
    }

    private static Payment newPayment() {
        PaymentRequest request = new PaymentRequest(Money.parse("100.00", Currency.EUR), CountryCode.of("ES"),
                "pedido-1", IdempotencyKey.of("k-" + UUID.randomUUID()));
        return Payment.create(PaymentId.of(UUID.randomUUID()), request, T0);
    }

    private static GatewayAttempt attempt(AttemptOutcome outcome, String reference, Instant at) {
        return new GatewayAttempt(STRIPE, outcome, Duration.ofNanos(123_456_789), Optional.of("code"),
                Optional.ofNullable(reference), at);
    }

    @Test
    @DisplayName("guarda y recupera el pago completo, con la versión avanzando en cada save")
    void saveAndFindRoundTrip() {
        Payment payment = newPayment();
        tx.inTransaction(() -> {
            payments.save(payment);
            return null;
        });
        assertThat(payment.version()).isEqualTo(1);

        payment.recordAttempt(attempt(AttemptOutcome.ERROR, null, T0.plusSeconds(1)));
        payment.recordAttempt(attempt(AttemptOutcome.APPROVED, "pi_1", T0.plusSeconds(2)));
        payment.authorize(STRIPE, "pi_1", T0.plusSeconds(2));
        payment.capture(T0.plusSeconds(3));
        payment.refund(Money.parse("30.00", Currency.EUR), "re_1", T0.plusSeconds(4));
        tx.inTransaction(() -> {
            payments.save(payment);
            return null;
        });
        assertThat(payment.version()).isEqualTo(2);

        Payment loaded = tx.inTransaction(() -> payments.findById(payment.id())).orElseThrow();
        assertThat(loaded.version()).isEqualTo(2);
        assertThat(loaded.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(loaded.attempts()).isEqualTo(payment.attempts());
        assertThat(loaded.refunds()).isEqualTo(payment.refunds());
        assertThat(loaded.refundedAmount()).isEqualTo(Money.parse("30.00", Currency.EUR));
        assertThat(loaded.request()).isEqualTo(payment.request());
        assertThat(loaded.updatedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(tx.inTransaction(() -> payments.findByGatewayReference(STRIPE, "pi_1")))
                .map(Payment::id).contains(payment.id());

        // Guardar sin cambios también hace avanzar la versión, como exige el puerto
        tx.inTransaction(() -> {
            payments.save(loaded);
            return null;
        });
        assertThat(loaded.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("una instancia desfasada o un doble insert lanzan ConcurrentPaymentModificationException sin escribir")
    void optimisticVersion() {
        Payment payment = newPayment();
        tx.inTransaction(() -> {
            payments.save(payment);
            return null;
        });
        Payment first = tx.inTransaction(() -> payments.findById(payment.id())).orElseThrow();
        Payment second = tx.inTransaction(() -> payments.findById(payment.id())).orElseThrow();

        first.fail("NO_GATEWAY_AVAILABLE", T0.plusSeconds(1));
        tx.inTransaction(() -> {
            payments.save(first);
            return null;
        });
        second.recordAttempt(attempt(AttemptOutcome.TIMEOUT, null, T0.plusSeconds(1)));
        assertThatThrownBy(() -> tx.inTransaction(() -> {
            payments.save(second);
            return null;
        })).isInstanceOf(ConcurrentPaymentModificationException.class);

        Payment duplicate = Payment.create(payment.id(), payment.request(), T0);
        assertThatThrownBy(() -> tx.inTransaction(() -> {
            payments.save(duplicate);
            return null;
        })).isInstanceOf(ConcurrentPaymentModificationException.class);

        Payment stored = tx.inTransaction(() -> payments.findById(payment.id())).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.attempts()).isEmpty();
        assertThat(stored.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("dos transacciones que guardan a la vez el mismo pago: gana una y la otra recibe CME")
    void concurrentUpdate() throws Exception {
        Payment payment = newPayment();
        tx.inTransaction(() -> {
            payments.save(payment);
            return null;
        });
        CountDownLatch bothLoaded = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    try {
                        tx.inTransaction(() -> {
                            Payment p = payments.findById(payment.id()).orElseThrow();
                            bothLoaded.countDown();
                            await(bothLoaded);
                            p.fail("ABANDONED", T0.plusSeconds(5));
                            payments.save(p);
                            return null;
                        });
                        return "ok";
                    } catch (ConcurrentPaymentModificationException e) {
                        return "cme";
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "cme");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("tryReserve concurrente con la misma clave: exactamente una reserva")
    void concurrentReservation() throws Exception {
        IdempotencyKey key = IdempotencyKey.of("concurrente-" + UUID.randomUUID());
        Payment template = newPayment();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Reservation>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PaymentId candidate = PaymentId.of(UUID.randomUUID());
                futures.add(pool.submit(() -> {
                    await(start);
                    return tx.inTransaction(() ->
                            idempotency.tryReserve(key, template.request().fingerprint(), candidate, T0));
                }));
            }
            start.countDown();
            List<Reservation> results = new ArrayList<>();
            for (Future<Reservation> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(r -> r instanceof Reservation.Reserved).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof Reservation.InProgress).hasSize(threads - 1);
            PaymentId winner = ((Reservation.InProgress) results.stream()
                    .filter(r -> r instanceof Reservation.InProgress).findFirst().orElseThrow()).paymentId();
            tx.inTransaction(() -> {
                idempotency.complete(key, winner);
                return null;
            });
            assertThat(tx.inTransaction(() -> idempotency.tryReserve(key, template.request().fingerprint(),
                    PaymentId.of(UUID.randomUUID()), T0)))
                    .isEqualTo(new Reservation.AlreadyCompleted(template.request().fingerprint(), winner));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("una reserva deshecha con su transacción no deja la clave ocupada")
    void reservationRollsBack() {
        IdempotencyKey key = IdempotencyKey.of("rollback-" + UUID.randomUUID());
        Payment template = newPayment();
        assertThatThrownBy(() -> tx.inTransaction(() -> {
            idempotency.tryReserve(key, template.request().fingerprint(), template.id(), T0);
            throw new IllegalStateException("falla después de reservar");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tx.inTransaction(() -> idempotency.tryReserve(key, template.request().fingerprint(),
                template.id(), T0))).isInstanceOf(Reservation.Reserved.class);
    }

    @Test
    @DisplayName("las notificaciones se marcan una sola vez por (pasarela, id)")
    void processedNotifications() {
        String id = "evt_" + UUID.randomUUID();
        assertThat(tx.inTransaction(() -> processed.markProcessed(STRIPE, id))).isTrue();
        assertThat(tx.inTransaction(() -> processed.markProcessed(STRIPE, id))).isFalse();
        assertThat(tx.inTransaction(() -> processed.markProcessed(GatewayId.of("adyen"), id))).isTrue();
    }

    @Test
    @DisplayName("el outbox se escribe en la misma transacción: con rollback no queda fila; fuera de una, falla")
    void outboxInSameTransaction() {
        Payment payment = newPayment();
        assertThatThrownBy(() -> tx.inTransaction(() -> {
            payments.save(payment);
            publisher.publish(payment.pullEvents());
            throw new IllegalStateException("algo falla antes de confirmar");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
        assertThat(tx.inTransaction(() -> payments.findById(payment.id()))).isEmpty();

        Payment committed = newPayment();
        tx.inTransaction(() -> {
            payments.save(committed);
            publisher.publish(committed.pullEvents());
            return null;
        });
        List<OutboxMessage> pending = tx.inTransaction(() -> outbox.fetchUnpublished(10));
        assertThat(pending).singleElement().satisfies(m -> {
            assertThat(m.event().eventType()).isEqualTo("PaymentCreated");
            assertThat(m.event().aggregateId()).isEqualTo(committed.id().toString());
            assertThat(m.event().payload()).containsEntry("merchantReference", "pedido-1")
                    .containsEntry("amount", java.util.Map.of("amount", "100.00", "currency", "EUR"));
        });

        assertThatThrownBy(() -> publisher.publish(List.of(new PaymentCaptured(committed.id(),
                committed.amount(), T0)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("relay con SKIP LOCKED: dos lectores a la vez se reparten las filas sin repetir")
    void relaySkipLocked() throws Exception {
        tx.inTransaction(() -> {
            List<dev.pedrogomez.payaf.domain.event.DomainEvent> events = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                events.add(new CircuitOpened(GatewayId.of("g" + i), T0.plusSeconds(i)));
            }
            publisher.publish(events);
            return null;
        });

        CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(() -> tx.inTransaction(() -> {
            List<OutboxMessage> batch = outbox.fetchUnpublished(3);
            firstHoldsLocks.countDown();
            await(secondDone);
            outbox.markPublished(batch.stream().map(OutboxMessage::id).toList());
            return batch.stream().map(OutboxMessage::id).toList();
        }));
        await(firstHoldsLocks);
        List<Long> second = tx.inTransaction(() -> {
            List<OutboxMessage> batch = outbox.fetchUnpublished(10);
            outbox.markPublished(batch.stream().map(OutboxMessage::id).toList());
            return batch.stream().map(OutboxMessage::id).toList();
        });
        secondDone.countDown();
        List<Long> firstIds = first.get(30, TimeUnit.SECONDS);

        assertThat(firstIds).hasSize(3);
        assertThat(second).hasSize(2).doesNotContainAnyElementsOf(firstIds);
        assertThat(tx.inTransaction(() -> outbox.fetchUnpublished(10))).isEmpty();
    }

    @Test
    @DisplayName("el breaker se guarda en cada save y se recupera al crear otro almacén (un reinicio)")
    void circuitBreakerSurvivesRestart() {
        GatewayId gateway = GatewayId.of("redsys-" + UUID.randomUUID().toString().substring(0, 8));
        JdbcCircuitBreakerStore store = new JdbcCircuitBreakerStore(jdbc, json, JpaTestApplication.BREAKER);
        CircuitBreaker breaker = store.get(gateway);
        assertThat(store.get(gateway)).isSameAs(breaker);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(now);
        }
        store.save(breaker);

        JdbcCircuitBreakerStore restarted = new JdbcCircuitBreakerStore(jdbc, json, JpaTestApplication.BREAKER);
        CircuitBreaker restored = restarted.get(gateway);
        assertThat(restored.state()).isEqualTo(CircuitState.OPEN);
        assertThat(restored.snapshot()).isEqualTo(breaker.snapshot());
        assertThat(restored.pullEvents()).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Tiempo de espera agotado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
