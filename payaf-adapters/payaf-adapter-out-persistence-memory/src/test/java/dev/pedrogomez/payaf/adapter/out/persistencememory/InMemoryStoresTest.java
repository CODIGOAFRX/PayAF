package dev.pedrogomez.payaf.adapter.out.persistencememory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.port.out.IdempotencyStore.Reservation;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryStoresTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    private static final RequestFingerprint FINGERPRINT = new RequestFingerprint("a".repeat(64));
    private static final RequestFingerprint OTHER_FINGERPRINT = new RequestFingerprint("b".repeat(64));

    private static PaymentId newId() {
        return PaymentId.of(UUID.randomUUID());
    }

    @Nested
    @DisplayName("IdempotencyStore")
    class Idempotency {

        private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        private final IdempotencyKey key = IdempotencyKey.of("k1");

        @Test
        @DisplayName("ciclo de vida: reservada → en curso → completada")
        void lifecycle() {
            PaymentId id = newId();

            assertThat(store.tryReserve(key, FINGERPRINT, id, T0)).isInstanceOf(Reservation.Reserved.class);
            assertThat(store.tryReserve(key, OTHER_FINGERPRINT, newId(), T0))
                    .isEqualTo(new Reservation.InProgress(FINGERPRINT, id));

            store.complete(key, id);
            store.complete(key, id);

            assertThat(store.isCompleted(key)).isTrue();
            assertThat(store.tryReserve(key, FINGERPRINT, newId(), T0))
                    .isEqualTo(new Reservation.AlreadyCompleted(FINGERPRINT, id));
        }

        @Test
        @DisplayName("completar una clave que no existe o de otro pago es un error")
        void completeRequiresReservation() {
            assertThatThrownBy(() -> store.complete(key, newId())).isInstanceOf(IllegalStateException.class);
            store.tryReserve(key, FINGERPRINT, newId(), T0);
            assertThatThrownBy(() -> store.complete(key, newId())).isInstanceOf(IllegalStateException.class);
            assertThat(store.isCompleted(key)).isFalse();
        }

        @Test
        @DisplayName("tryReserve es atómico: 32 hilos con la misma clave, exactamente uno la reserva")
        void concurrentReservation() throws Exception {
            int threads = 32;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Reservation>> results = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    PaymentId candidate = newId();
                    results.add(pool.submit(() -> {
                        start.await();
                        return store.tryReserve(key, FINGERPRINT, candidate, T0);
                    }));
                }
                start.countDown();
                List<Reservation> reservations = new ArrayList<>();
                for (Future<Reservation> result : results) {
                    reservations.add(result.get(10, TimeUnit.SECONDS));
                }
                assertThat(reservations).filteredOn(Reservation.Reserved.class::isInstance).hasSize(1);
                assertThat(reservations).filteredOn(Reservation.InProgress.class::isInstance).hasSize(threads - 1);
                // Todos los que pierden ven al mismo ganador.
                assertThat(reservations.stream().filter(Reservation.InProgress.class::isInstance)
                        .map(r -> ((Reservation.InProgress) r).paymentId()).distinct()).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(store.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("CircuitBreakerStore")
    class Breakers {

        private final CircuitBreakerConfig config = CircuitBreakerConfig.of(3, Duration.ofSeconds(15));
        private final InMemoryCircuitBreakerStore store = new InMemoryCircuitBreakerStore(config);

        @Test
        @DisplayName("siempre la misma instancia por pasarela, creada con la configuración inyectada")
        void sameInstance() {
            GatewayId stripe = GatewayId.of("stripe");
            CircuitBreaker breaker = store.get(stripe);

            assertThat(store.get(stripe)).isSameAs(breaker);
            assertThat(breaker.config()).isEqualTo(config);
            assertThat(store.get(GatewayId.of("adyen"))).isNotSameAs(breaker);
            store.save(breaker);
            assertThatThrownBy(() -> store.save(new CircuitBreaker(stripe, config)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("muchos hilos piden el breaker a la vez y todos reciben la misma instancia")
        void concurrentGet() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<CircuitBreaker>> results = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    results.add(pool.submit(() -> store.get(GatewayId.of("redsys"))));
                }
                CircuitBreaker first = results.getFirst().get(10, TimeUnit.SECONDS);
                for (Future<CircuitBreaker> result : results) {
                    assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("ProcessedNotificationStore")
    class Notifications {

        @Test
        @DisplayName("marca una vez; el mismo id en otra pasarela es otra notificación")
        void marksOnce() {
            InMemoryProcessedNotificationStore store = new InMemoryProcessedNotificationStore();

            assertThat(store.markProcessed(GatewayId.of("stripe"), "evt_1")).isTrue();
            assertThat(store.markProcessed(GatewayId.of("stripe"), "evt_1")).isFalse();
            assertThat(store.markProcessed(GatewayId.of("adyen"), "evt_1")).isTrue();
        }
    }

    @Nested
    @DisplayName("EventPublisher")
    class Events {

        private static DomainEvent event(int i) {
            return new PaymentFailed(newId(), "R" + i, T0);
        }

        @Test
        @DisplayName("guarda los eventos en orden y como mucho los últimos 1000")
        void bounded() {
            InMemoryEventPublisher publisher = new InMemoryEventPublisher();
            List<DomainEvent> all = IntStream.range(0, 1_200).mapToObj(Events::event).toList();

            publisher.publish(all.subList(0, 700));
            publisher.publish(List.of());
            publisher.publish(all.subList(700, 1_200));

            assertThat(publisher.recent()).hasSize(InMemoryEventPublisher.CAPACITY)
                    .containsExactlyElementsOf(all.subList(200, 1_200));
        }
    }

    @Nested
    @DisplayName("TransactionRunner")
    class Transactions {

        private final InMemoryTransactionRunner runner = new InMemoryTransactionRunner();

        @Test
        @DisplayName("devuelve el resultado, admite anidar y propaga la excepción tal cual")
        void basics() {
            assertThat(runner.inTransaction(() -> runner.inTransaction(() -> 42))).isEqualTo(42);
            IllegalStateException boom = new IllegalStateException("boom");
            assertThatThrownBy(() -> runner.inTransaction(() -> {
                throw boom;
            })).isSameAs(boom);
            assertThat(runner.isActive()).isFalse();
        }

        @Test
        @DisplayName("NO deshace: lo escrito antes de la excepción se queda")
        void noRollback() {
            List<String> writes = new ArrayList<>();
            assertThatThrownBy(() -> runner.inTransaction(() -> {
                writes.add("escrito");
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(writes).containsExactly("escrito");
        }

        @Test
        @DisplayName("serializa: nunca hay dos transacciones dentro a la vez")
        void serializes() throws Exception {
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger maxInside = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    futures.add(pool.submit(() -> runner.inTransaction(() -> {
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        Thread.onSpinWait();
                        inside.decrementAndGet();
                        return null;
                    })));
                }
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(maxInside).hasValue(1);
        }
    }
}
