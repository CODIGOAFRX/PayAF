package dev.pedrogomez.payaf.adapter.out.persistencememory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore.Reservation;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Tester (fase 4a): casos feos de los adaptadores de memoria que no cubren los tests del
 * desarrollador.
 */
class InMemoryAdaptersAttackTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    private static final GatewayId STRIPE = GatewayId.of("stripe");

    private static Payment newPayment(String key, Instant at) {
        PaymentRequest request = new PaymentRequest(Money.of(10_000, Currency.EUR), CountryCode.of("ES"),
                "pedido-" + key, IdempotencyKey.of(key));
        return Payment.create(PaymentId.of(UUID.randomUUID()), request, at);
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------
    // PaymentRepository
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("save con versión obsoleta: CONCURRENT_MODIFICATION, nada cambia y la instancia conserva su versión")
    void staleSaveChangesNothing() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        Payment original = newPayment("k", T0);
        repo.save(original);
        Payment a = repo.findById(original.id()).orElseThrow();
        Payment b = repo.findById(original.id()).orElseThrow();
        a.fail("A", T0.plusSeconds(1));
        repo.save(a);
        b.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.APPROVED, Duration.ofMillis(1), Optional.empty(),
                Optional.of("pi_b"), T0.plusSeconds(2)));
        b.authorize(STRIPE, "pi_b", T0.plusSeconds(2));

        assertThatThrownBy(() -> repo.save(b))
                .isInstanceOf(ConcurrentPaymentModificationException.class)
                .satisfies(e -> assertThat(((ApplicationException) e).code())
                        .isEqualTo(ApplicationException.CONCURRENT_MODIFICATION));

        Payment stored = repo.findById(original.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.failureReason()).contains("A");
        assertThat(stored.attempts()).isEmpty();
        assertThat(stored.version()).isEqualTo(2);
        assertThat(b.version()).as("la instancia perdedora no avanza su versión").isEqualTo(1);
        assertThat(repo.findByGatewayReference(STRIPE, "pi_b")).isEmpty();
    }

    @Test
    @DisplayName("una versión del futuro (mayor que la guardada) también es un conflicto")
    void futureVersionIsAConflict() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        Payment p = newPayment("k", T0);
        repo.save(p);
        p.markPersisted(5);

        assertThatThrownBy(() -> repo.save(p)).isInstanceOf(ConcurrentPaymentModificationException.class);
        assertThat(repo.findById(p.id()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("copias defensivas: cambiar la instancia pasada a save o la devuelta no altera lo guardado")
    void defensiveCopiesBothWays() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        Payment saved = newPayment("k", T0);
        repo.save(saved);
        saved.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.ERROR, Duration.ofMillis(1), Optional.empty(),
                Optional.empty(), T0.plusSeconds(1)));
        saved.markPersisted(99);

        Payment read = repo.findById(saved.id()).orElseThrow();
        read.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.TIMEOUT, Duration.ofMillis(1), Optional.empty(),
                Optional.empty(), T0.plusSeconds(2)));
        read.fail("X", T0.plusSeconds(3));
        read.attempts(); // cualquier lista que devuelva no es una vista de lo guardado

        Payment again = repo.findById(saved.id()).orElseThrow();
        assertThat(again.attempts()).isEmpty();
        assertThat(again.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(again.version()).isEqualTo(1);
        assertThat(again.updatedAt()).isEqualTo(T0);
        // findPendingOlderThan y findRecent también devuelven copias.
        repo.findRecent(10).getFirst().fail("Y", T0.plusSeconds(4));
        repo.findPendingOlderThan(T0.plusSeconds(1), 10).getFirst().fail("Z", T0.plusSeconds(4));
        assertThat(repo.findById(saved.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("findPendingOlderThan: corte estricto, solo PENDING, del más viejo al más nuevo y respeta el límite")
    void findPendingOlderThanBoundaries() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        List<Payment> pendings = new ArrayList<>();
        // Se insertan desordenados para que el orden no salga «gratis» del orden de inserción.
        for (int s : new int[] {30, 10, 50, 20, 40}) {
            Payment p = newPayment("p" + s, T0.plusSeconds(s));
            repo.save(p);
            pendings.add(p);
        }
        Payment closed = newPayment("closed", T0);
        closed.fail("X", T0);
        repo.save(closed);
        // Un pago creado hace mucho pero actualizado hace poco no es viejo: manda updatedAt.
        Payment touched = newPayment("touched", T0);
        touched.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.ERROR, Duration.ofMillis(1),
                Optional.empty(), Optional.empty(), T0.plusSeconds(100)));
        repo.save(touched);

        List<Instant> updated = repo.findPendingOlderThan(T0.plusSeconds(40), 10).stream()
                .map(Payment::updatedAt).toList();
        assertThat(updated).containsExactly(T0.plusSeconds(10), T0.plusSeconds(20), T0.plusSeconds(30));
        assertThat(repo.findPendingOlderThan(T0.plusSeconds(10), 10)).isEmpty();
        assertThat(repo.findPendingOlderThan(T0.plusSeconds(1000), 2)).extracting(Payment::updatedAt)
                .containsExactly(T0.plusSeconds(10), T0.plusSeconds(20));
        assertThat(repo.findPendingOlderThan(T0.plusSeconds(1000), 100)).hasSize(6)
                .noneMatch(p -> p.id().equals(closed.id()));
        assertThat(repo.findPendingOlderThan(T0.plusSeconds(100), 100))
                .noneMatch(p -> p.id().equals(touched.id()));
        assertThatThrownBy(() -> repo.findPendingOlderThan(T0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.findPendingOlderThan(T0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.findPendingOlderThan(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("nulos: save, findById y findByGatewayReference rechazan null")
    void nulls() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        assertThatThrownBy(() -> repo.save(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repo.findById(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repo.findByGatewayReference(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repo.findByGatewayReference(STRIPE, null)).isInstanceOf(NullPointerException.class);
        assertThat(repo.findById(PaymentId.of(UUID.randomUUID()))).isEmpty();
    }

    @RepeatedTest(5)
    @DisplayName("32 hilos insertan el mismo pago nuevo a la vez: uno gana y queda versión 1")
    void concurrentInsertOfSameNewPayment() throws Exception {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        Payment template = newPayment("k", T0);
        List<Boolean> wins = runConcurrently(32, () -> {
            Payment copy = Payment.rehydrate(template.id(), template.request(), template.status(), List.of(), null,
                    null, null, template.refundedAmount(), List.of(), T0, T0, 0);
            try {
                repo.save(copy);
                return true;
            } catch (ConcurrentPaymentModificationException e) {
                return false;
            }
        });
        assertThat(wins.stream().filter(w -> w).count()).isEqualTo(1);
        assertThat(repo.findById(template.id()).orElseThrow().version()).isEqualTo(1);
        assertThat(repo.size()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // IdempotencyStore
    // ---------------------------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("tryReserve con 64 hilos y candidatos distintos: uno Reserved y los demás InProgress apuntando al ganador")
    void tryReserveAtomicWithDistinctCandidates() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyKey key = IdempotencyKey.of("same");
        PaymentRequest request = new PaymentRequest(Money.of(100, Currency.EUR), CountryCode.of("ES"), "p",
                key);
        record Try(PaymentId candidate, Reservation reservation) {
        }
        List<Try> tries = runConcurrently(64, () -> {
            PaymentId candidate = PaymentId.of(UUID.randomUUID());
            return new Try(candidate, store.tryReserve(key, request.fingerprint(), candidate, T0));
        });

        List<Try> reserved = tries.stream().filter(t -> t.reservation() instanceof Reservation.Reserved).toList();
        assertThat(reserved).hasSize(1);
        PaymentId winner = reserved.getFirst().candidate();
        assertThat(tries).filteredOn(t -> !(t.reservation() instanceof Reservation.Reserved))
                .hasSize(63)
                .allSatisfy(t -> assertThat(t.reservation()).isInstanceOfSatisfying(Reservation.InProgress.class,
                        p -> assertThat(p.paymentId()).isEqualTo(winner)));
        assertThat(store.size()).isEqualTo(1);

        store.complete(key, winner);
        assertThat(store.tryReserve(key, request.fingerprint(), PaymentId.of(UUID.randomUUID()), T0))
                .isInstanceOfSatisfying(Reservation.AlreadyCompleted.class,
                        c -> assertThat(c.paymentId()).isEqualTo(winner));
        // Completar dos veces para el mismo pago no hace nada; para otro, error y no cambia.
        store.complete(key, winner);
        assertThatThrownBy(() -> store.complete(key, PaymentId.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.isCompleted(key)).isTrue();
    }

    // ---------------------------------------------------------------------------------
    // CircuitBreakerStore
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("CircuitBreakerStore devuelve la misma instancia y rechaza guardar otra distinta")
    void breakerStoreSameInstance() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.of(3, Duration.ofSeconds(15));
        InMemoryCircuitBreakerStore store = new InMemoryCircuitBreakerStore(config);
        GatewayId redsys = GatewayId.of("redsys");

        Set<CircuitBreaker> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seen.addAll(runConcurrently(32, () -> store.get(redsys)));
        assertThat(seen).hasSize(1);
        CircuitBreaker breaker = seen.iterator().next();
        assertThat(breaker.config()).isEqualTo(config);

        breaker.recordFailure(T0);
        store.save(breaker);
        assertThat(store.get(redsys)).isSameAs(breaker);
        assertThatThrownBy(() -> store.save(new CircuitBreaker(redsys, config)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(redsys)).isSameAs(breaker);
        assertThat(store.get(GatewayId.of("adyen"))).isNotSameAs(breaker);
    }

    // ---------------------------------------------------------------------------------
    // ProcessedNotificationStore
    // ---------------------------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("ProcessedNotificationStore: 64 hilos marcan la misma notificación y solo uno recibe true")
    void processedNotificationIsAtomic() throws Exception {
        InMemoryProcessedNotificationStore store = new InMemoryProcessedNotificationStore();
        List<Boolean> results = runConcurrently(64, () -> store.markProcessed(STRIPE, "evt_1"));
        assertThat(results.stream().filter(r -> r).count()).isEqualTo(1);
        assertThat(store.markProcessed(GatewayId.of("adyen"), "evt_1")).isTrue();
        assertThat(store.size()).isEqualTo(2);
        assertThatThrownBy(() -> store.markProcessed(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.markProcessed(STRIPE, null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------
    // EventPublisher
    // ---------------------------------------------------------------------------------

    private static DomainEvent event(int n) {
        return new PaymentFailed(PaymentId.of(new UUID(0, n)), "E" + n, T0.plusSeconds(n));
    }

    @Test
    @DisplayName("EventPublisher: el 1001 expulsa al más viejo y recent() es inmodificable y no es una vista")
    void eventPublisherBounded() {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        for (int i = 1; i <= 1000; i++) {
            publisher.publish(List.of(event(i)));
        }
        assertThat(publisher.recent()).hasSize(1000).first().isEqualTo(event(1));

        publisher.publish(List.of(event(1001)));

        List<DomainEvent> recent = publisher.recent();
        assertThat(recent).hasSize(InMemoryEventPublisher.CAPACITY);
        assertThat(recent.getFirst()).isEqualTo(event(2));
        assertThat(recent.getLast()).isEqualTo(event(1001));
        assertThatThrownBy(() -> recent.add(event(9))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(recent::clear).isInstanceOf(UnsupportedOperationException.class);
        publisher.publish(List.of(event(1002)));
        assertThat(recent.getLast()).as("la lista devuelta es una copia").isEqualTo(event(1001));
    }

    @Test
    @DisplayName("EventPublisher: un lote mayor que la capacidad deja los últimos 1000 del lote en orden")
    void eventPublisherHugeBatch() {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        List<DomainEvent> batch = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            batch.add(event(i));
        }
        publisher.publish(batch);
        List<DomainEvent> recent = publisher.recent();
        assertThat(recent).hasSize(1000);
        assertThat(recent.getFirst()).isEqualTo(event(1501));
        assertThat(recent.getLast()).isEqualTo(event(2500));
        publisher.publish(List.of());
        assertThat(publisher.recent()).hasSize(1000);
        assertThatThrownBy(() -> publisher.publish(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("EventPublisher: 16 hilos publican a la vez sin perder ni repetir eventos (por debajo de la capacidad)")
    void eventPublisherConcurrent() throws Exception {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        runConcurrently(16, () -> {
            for (int i = 0; i < 50; i++) {
                publisher.publish(List.of(event(seq.incrementAndGet())));
            }
            return null;
        });
        assertThat(new HashSet<>(publisher.recent())).hasSize(800);
    }
}
