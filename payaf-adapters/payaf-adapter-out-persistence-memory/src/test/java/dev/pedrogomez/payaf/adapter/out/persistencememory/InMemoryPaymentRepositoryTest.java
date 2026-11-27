package dev.pedrogomez.payaf.adapter.out.persistencememory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryPaymentRepositoryTest {

    static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    static final GatewayId STRIPE = GatewayId.of("stripe");

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();

    static Payment newPayment(String key, Instant at) {
        PaymentRequest request = new PaymentRequest(Money.of(10_000, Currency.EUR), CountryCode.of("ES"),
                "pedido-" + key, IdempotencyKey.of(key));
        return Payment.create(PaymentId.of(UUID.randomUUID()), request, at);
    }

    @Test
    @DisplayName("un pago nuevo (versión 0) se guarda con la versión 1 y la instancia se entera")
    void insert() {
        Payment payment = newPayment("k1", T0);

        repository.save(payment);

        assertThat(payment.version()).isEqualTo(1);
        assertThat(repository.findById(payment.id()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma instancia se puede guardar varias veces seguidas: cada save parte del anterior")
    void saveTwice() {
        Payment payment = newPayment("k1", T0);
        repository.save(payment);
        payment.authorize(STRIPE, "ch_1", T0.plusSeconds(1));

        repository.save(payment);

        assertThat(payment.version()).isEqualTo(2);
        assertThat(repository.findById(payment.id()).orElseThrow().status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("guardar desde una versión vieja lanza CONCURRENT_MODIFICATION y no escribe")
    void staleVersion() {
        Payment payment = newPayment("k1", T0);
        repository.save(payment);
        Payment first = repository.findById(payment.id()).orElseThrow();
        Payment second = repository.findById(payment.id()).orElseThrow();

        first.authorize(STRIPE, "ch_1", T0.plusSeconds(1));
        repository.save(first);
        second.fail("NO_GATEWAY_AVAILABLE", T0.plusSeconds(1));

        assertThatThrownBy(() -> repository.save(second)).isInstanceOf(ConcurrentPaymentModificationException.class);
        assertThat(second.version()).isEqualTo(1);
        Payment stored = repository.findById(payment.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(stored.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("insertar dos veces el mismo pago nuevo es un conflicto")
    void doubleInsert() {
        Payment payment = newPayment("k1", T0);
        repository.save(payment);
        Payment sameIdNew = Payment.rehydrate(payment.id(), payment.request(), PaymentStatus.PENDING, List.of(), null,
                null, null, Money.zero(Currency.EUR), List.of(), T0, T0, 0);

        assertThatThrownBy(() -> repository.save(sameIdNew))
                .isInstanceOf(ConcurrentPaymentModificationException.class);
    }

    @Test
    @DisplayName("copias defensivas: ni la instancia guardada ni la leída comparten estado con lo almacenado")
    void defensiveCopies() {
        Payment payment = newPayment("k1", T0);
        repository.save(payment);

        // Cambiar la instancia que se guardó no cambia lo guardado.
        payment.authorize(STRIPE, "ch_1", T0.plusSeconds(1));
        assertThat(repository.findById(payment.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);

        // Cambiar una instancia leída tampoco.
        Payment read = repository.findById(payment.id()).orElseThrow();
        read.fail("X", T0.plusSeconds(2));
        assertThat(repository.findById(payment.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(repository.findById(payment.id()).orElseThrow()).isNotSameAs(repository.findById(payment.id())
                .orElseThrow());
        // Y la copia no arrastra los eventos pendientes.
        assertThat(repository.findById(payment.id()).orElseThrow().pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("búsquedas por referencia, recientes y PENDING viejos")
    void queries() {
        Payment old = newPayment("old", T0);
        repository.save(old);
        Payment authorized = newPayment("auth", T0.plusSeconds(10));
        authorized.recordAttempt(new GatewayAttempt(STRIPE, AttemptOutcome.APPROVED, Duration.ofMillis(5),
                Optional.empty(), Optional.of("ch_9"), T0.plusSeconds(10)));
        authorized.authorize(STRIPE, "ch_9", T0.plusSeconds(10));
        repository.save(authorized);
        Payment young = newPayment("young", T0.plusSeconds(100));
        repository.save(young);

        assertThat(repository.findByGatewayReference(STRIPE, "ch_9")).map(Payment::id).contains(authorized.id());
        assertThat(repository.findByGatewayReference(GatewayId.of("adyen"), "ch_9")).isEmpty();
        assertThat(repository.findRecent(2)).extracting(Payment::id).containsExactly(young.id(), authorized.id());
        assertThat(repository.findPendingOlderThan(T0.plusSeconds(50), 10)).extracting(Payment::id)
                .containsExactly(old.id());
        assertThat(repository.findPendingOlderThan(T0, 10)).isEmpty();
        assertThat(repository.findPendingOlderThan(T0.plusSeconds(500), 1)).extracting(Payment::id)
                .containsExactly(old.id());
        assertThatThrownBy(() -> repository.findRecent(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("muchos hilos guardan a la vez desde la misma versión: exactamente uno gana")
    void concurrentSaves() throws Exception {
        Payment payment = newPayment("k1", T0);
        repository.save(payment);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Payment copy = repository.findById(payment.id()).orElseThrow();
                String reference = "ch_" + i;
                results.add(pool.submit(() -> {
                    copy.authorize(STRIPE, reference, T0.plusSeconds(1));
                    start.await();
                    try {
                        repository.save(copy);
                        return true;
                    } catch (ConcurrentPaymentModificationException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(repository.findById(payment.id()).orElseThrow().version()).isEqualTo(2);
    }
}
