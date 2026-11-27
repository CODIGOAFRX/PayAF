package dev.pedrogomez.payaf.adapter.out.persistencememory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.service.PaymentOperationsService;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentEvent;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tester (fase 4a): operaciones concurrentes sobre el mismo pago a través de los casos de uso
 * reales y del {@link InMemoryPaymentRepository} real (no el fake de la aplicación).
 * <p>
 * Regla vigente: <strong>un APPROVED de la PSP nunca se pierde por un CONCURRENT_MODIFICATION</strong>.
 * Tras un CME se recarga y se vuelve a aplicar (como mucho 5 veces): el reembolso es idempotente
 * por referencia; una captura o anulación que ya está en el estado destino se devuelve sin error;
 * cualquier otro rechazo del dominio sale como {@link DomainException}; si se agotan los intentos,
 * OPERATION_UNRECORDED. Por eso aquí un CME nunca debe llegar al llamante.
 * <p>
 * La pasarela de estos tests es un fake que aprueba todo (no tiene libro). Lo que aprueba una PSP
 * de verdad, con su libro, se comprueba en bootstrap con el simulador
 * ({@code MemoryProfileEndToEndTest}).
 */
class ConcurrentOperationsOverRealRepositoryTest {

    /** Lo que devuelve un hilo: el resultado o la excepción. */
    private record Outcome(String op, Object value) {
        boolean ok() {
            return !(value instanceof Throwable);
        }
    }

    private static List<Outcome> runAll(List<String> names, List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                outcomes.add(new Outcome(names.get(i), futures.get(i).get(20, TimeUnit.SECONDS)));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Outcome> runN(int n, String name, Callable<Object> task) throws Exception {
        List<String> names = new ArrayList<>();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add(name);
            tasks.add(task);
        }
        return runAll(names, tasks);
    }

    /** Hace que todas las operaciones esperen en la PSP hasta que hayan llegado todas. */
    private static void allMeetAtGateway(RealAdaptersHarness h, int parties) {
        CyclicBarrier barrier = new CyclicBarrier(parties);
        AtomicInteger seq = new AtomicInteger();
        h.gateway.onOperation = op -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return GatewayResponse.approved(op + "-" + seq.incrementAndGet(), Duration.ofMillis(1));
        };
    }

    /**
     * Con la regla de reintentos, un CME nunca llega al llamante: solo puede salir un rechazo del
     * dominio (en el ensayo o al reaplicar tras recargar).
     */
    private static void assertOnlyDomainRejections(List<Outcome> outcomes) {
        for (Outcome o : outcomes) {
            if (!o.ok()) {
                assertThat(o.value())
                        .as("fallo inesperado en %s: %s", o.op(), o.value())
                        .isInstanceOf(DomainException.class)
                        .isNotInstanceOf(ConcurrentPaymentModificationException.class);
            }
        }
    }

    private static Payment reload(RealAdaptersHarness h, PaymentId id) {
        // Recargar: si el pago guardado fuera incoherente, rehydrate lanzaría aquí.
        return h.payments.findById(id).orElseThrow();
    }

    @RepeatedTest(10)
    @DisplayName("N capturas aprobadas a la vez desde la misma versión: todas OK y CAPTURED, un solo PaymentCaptured, versión +1")
    void manyConcurrentCaptures() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        long versionBefore = reload(h, id).version();
        int n = 6;
        allMeetAtGateway(h, n);

        List<Outcome> outcomes = runN(n, "capture", () -> h.operations.capture(id));

        assertOnlyDomainRejections(outcomes);
        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes).allSatisfy(o -> assertThat(((PaymentView) o.value()).status())
                .isEqualTo(PaymentStatus.CAPTURED));
        Payment stored = reload(h, id);
        assertThat(stored.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(stored.version()).as("la captura se aplica una sola vez").isEqualTo(versionBefore + 1);
        assertThat(h.eventsOf(id, PaymentCaptured.class)).hasSize(1);
    }

    @RepeatedTest(10)
    @DisplayName("N anulaciones aprobadas a la vez: todas OK y CANCELED, un solo PaymentCanceled, versión +1")
    void manyConcurrentCancels() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        long versionBefore = reload(h, id).version();
        allMeetAtGateway(h, 4);

        List<Outcome> outcomes = runN(4, "cancel", () -> h.operations.cancel(id));

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(reload(h, id).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(reload(h, id).version()).isEqualTo(versionBefore + 1);
        assertThat(h.eventsOf(id, PaymentCanceled.class)).hasSize(1);
    }

    @RepeatedTest(10)
    @DisplayName("captura y anulación aprobadas a la vez: una gana, la otra sale como rechazo del dominio y los eventos cuadran")
    void captureVersusCancel() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        allMeetAtGateway(h, 2);

        List<Outcome> outcomes = runAll(List.of("capture", "cancel"),
                List.of(() -> h.operations.capture(id), () -> h.operations.cancel(id)));

        assertOnlyDomainRejections(outcomes);
        List<Outcome> winners = outcomes.stream().filter(Outcome::ok).toList();
        assertThat(winners).hasSize(1);
        Payment stored = reload(h, id);
        if (winners.getFirst().op().equals("capture")) {
            assertThat(stored.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(h.eventsOf(id, PaymentCaptured.class)).hasSize(1);
            assertThat(h.eventsOf(id, PaymentCanceled.class)).isEmpty();
        } else {
            assertThat(stored.status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(h.eventsOf(id, PaymentCanceled.class)).hasSize(1);
            assertThat(h.eventsOf(id, PaymentCaptured.class)).isEmpty();
        }
    }

    @RepeatedTest(10)
    @DisplayName("N reembolsos compatibles aprobados a la vez (5 x 10 de 100): todos OK y se apuntan los cinco")
    void manyCompatibleRefunds() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        h.operations.capture(id);
        allMeetAtGateway(h, 5);

        List<Outcome> outcomes = runN(5, "refund", () -> h.operations.refund(id, Money.of(1_000, Currency.EUR)));

        assertThat(outcomes).allMatch(Outcome::ok);
        Payment stored = reload(h, id);
        assertThat(stored.refundedAmount()).isEqualTo(Money.of(5_000, Currency.EUR));
        assertThat(stored.refunds()).hasSize(5);
        assertThat(h.eventsOf(id, PaymentRefunded.class)).hasSize(5);
    }

    @RepeatedTest(10)
    @DisplayName("N reembolsos de 60 sobre 100 que el fake aprueba todos: solo cabe uno y el resto son rechazos del dominio")
    void manyExcessRefunds() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        h.operations.capture(id);
        int n = 5;
        allMeetAtGateway(h, n);

        List<Outcome> outcomes = runN(n, "refund", () -> h.operations.refund(id, Money.of(6_000, Currency.EUR)));

        assertOnlyDomainRejections(outcomes);
        assertThat(outcomes.stream().filter(Outcome::ok).count()).isEqualTo(1);
        Payment stored = reload(h, id);
        assertThat(stored.refundedAmount()).isEqualTo(Money.of(6_000, Currency.EUR));
        assertThat(stored.refunds()).hasSize(1);
        assertThat(h.eventsOf(id, PaymentRefunded.class)).hasSize(1);
    }

    @RepeatedTest(10)
    @DisplayName("reembolso y captura a la vez sobre un pago capturado: la captura es un rechazo del dominio y el reembolso se apunta")
    void refundVersusCapture() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        h.operations.capture(id);

        List<Outcome> outcomes = runAll(List.of("refund", "capture"),
                List.of(() -> h.operations.refund(id, Money.of(2_500, Currency.EUR)), () -> h.operations.capture(id)));

        assertOnlyDomainRejections(outcomes);
        assertThat(outcomes.get(0).ok()).isTrue();
        assertThat(outcomes.get(1).ok()).isFalse();
        assertThat(reload(h, id).refundedAmount()).isEqualTo(Money.of(2_500, Currency.EUR));
        assertThat(h.eventsOf(id, PaymentCaptured.class)).hasSize(1);
    }

    @Test
    @DisplayName("propiedad: 40 rondas de 8 hilos con capturas, anulaciones y reembolsos al azar mantienen el pago coherente")
    void randomMixedOperationsProperty() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        for (int round = 0; round < 40; round++) {
            RealAdaptersHarness h = new RealAdaptersHarness();
            PaymentId id = h.authorize("k" + round, 10_000).payment().id();
            boolean startCaptured = random.nextBoolean();
            if (startCaptured) {
                h.operations.capture(id);
            }
            // La PSP tarda un poco al azar, para que las cargas y los guardados se crucen.
            AtomicInteger seq = new AtomicInteger();
            h.gateway.onOperation = op -> {
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(3));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return GatewayResponse.approved(op + "-" + seq.incrementAndGet(), Duration.ofMillis(1));
            };

            List<String> names = new ArrayList<>();
            List<Callable<Object>> tasks = new ArrayList<>();
            List<Long> amounts = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int kind = random.nextInt(3);
                long amount = 1 + random.nextInt(6_000);
                amounts.add(amount);
                switch (kind) {
                    case 0 -> {
                        names.add("capture");
                        tasks.add(() -> h.operations.capture(id));
                    }
                    case 1 -> {
                        names.add("cancel");
                        tasks.add(() -> h.operations.cancel(id));
                    }
                    default -> {
                        names.add("refund");
                        tasks.add(() -> h.operations.refund(id, Money.of(amount, Currency.EUR)));
                    }
                }
            }
            List<Outcome> outcomes = runAll(names, tasks);
            String context = "semilla " + seed + ", ronda " + round + ", ops " + names;

            assertOnlyDomainRejections(outcomes);
            Payment stored = reload(h, id);
            long captures = outcomes.stream().filter(o -> o.ok() && o.op().equals("capture")).count();
            long cancels = outcomes.stream().filter(o -> o.ok() && o.op().equals("cancel")).count();
            long refundSum = 0;
            long refundOk = 0;
            for (int i = 0; i < outcomes.size(); i++) {
                if (outcomes.get(i).ok() && outcomes.get(i).op().equals("refund")) {
                    refundSum += amounts.get(i);
                    refundOk++;
                }
            }

            // Captura y anulación son excluyentes (varias capturas OK son la misma captura).
            assertThat(captures > 0 && cancels > 0).as(context).isFalse();
            if (cancels > 0) {
                assertThat(stored.status()).as(context).isEqualTo(PaymentStatus.CANCELED);
                assertThat(refundOk).as(context).isZero();
            }
            if (captures > 0) {
                assertThat(stored.status().isCaptured()).as(context).isTrue();
            }
            if (!startCaptured && captures == 0) {
                assertThat(refundOk).as(context).isZero();
            }
            // Todo lo que dijo OK está en el pago, exactamente una vez.
            assertThat(stored.refundedAmount().amountMinor()).as(context).isEqualTo(refundSum);
            assertThat(stored.refundedAmount().amountMinor()).as(context).isLessThanOrEqualTo(10_000);
            assertThat(h.eventsOf(id, PaymentRefunded.class)).as(context).hasSize((int) refundOk);
            assertThat(h.eventsOf(id, PaymentCaptured.class)).as(context)
                    .hasSize(startCaptured || captures > 0 ? 1 : 0);
            assertThat(h.eventsOf(id, PaymentCanceled.class)).as(context).hasSize(cancels > 0 ? 1 : 0);
        }
    }

    // ---------------------------------------------------------------------------------
    // Límite de reintentos: OPERATION_UNRECORDED
    // ---------------------------------------------------------------------------------

    /** Repositorio real cuyo {@code save} lanza CME las primeras {@code failures} veces, sin escribir. */
    private static final class FlakySaveRepository implements PaymentRepository {

        private final InMemoryPaymentRepository real;
        volatile int failures;
        final AtomicInteger saves = new AtomicInteger();

        FlakySaveRepository(InMemoryPaymentRepository real) {
            this.real = real;
        }

        @Override
        public void save(Payment payment) {
            if (saves.incrementAndGet() <= failures) {
                throw new ConcurrentPaymentModificationException(payment.id());
            }
            real.save(payment);
        }

        @Override
        public Optional<Payment> findById(PaymentId id) {
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

    private record Flaky(RealAdaptersHarness h, FlakySaveRepository repo, PaymentOperationsService ops, PaymentId id) {
    }

    private static Flaky flaky(boolean captured) {
        RealAdaptersHarness h = new RealAdaptersHarness();
        PaymentId id = h.authorize("k", 10_000).payment().id();
        if (captured) {
            h.operations.capture(id);
        }
        FlakySaveRepository repo = new FlakySaveRepository(h.payments);
        PaymentOperationsService ops = new PaymentOperationsService(repo, h.catalog, h.breakers, h.events,
                h.transactions, h.clock);
        return new Flaky(h, repo, ops, id);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"capture", "cancel", "refund"})
    @DisplayName("4 CME seguidos tras el APPROVED: el 5.º intento lo apunta")
    void fourConflictsThenSuccess(String op) {
        Flaky f = flaky(op.equals("refund"));
        f.repo().failures = 4;
        Supplier<PaymentView> call = switch (op) {
            case "capture" -> () -> f.ops().capture(f.id());
            case "cancel" -> () -> f.ops().cancel(f.id());
            default -> () -> f.ops().refund(f.id(), Money.of(1_000, Currency.EUR));
        };

        PaymentView view = call.get();

        assertThat(f.repo().saves).hasValue(5);
        PaymentStatus expected = switch (op) {
            case "capture" -> PaymentStatus.CAPTURED;
            case "cancel" -> PaymentStatus.CANCELED;
            default -> PaymentStatus.PARTIALLY_REFUNDED;
        };
        assertThat(view.status()).isEqualTo(expected);
        assertThat(reload(f.h(), f.id()).status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"capture", "cancel", "refund"})
    @DisplayName("save siempre CME tras el APPROVED: OPERATION_UNRECORDED tras 5 intentos, con la referencia, y el pago intacto")
    void exhaustedAttempts(String op) {
        Flaky f = flaky(op.equals("refund"));
        Payment before = reload(f.h(), f.id());
        int eventsBefore = f.h().eventsOf(f.id(), PaymentEvent.class).size();
        f.repo().failures = Integer.MAX_VALUE;
        AtomicInteger seq = new AtomicInteger();
        f.h().gateway.onOperation = o -> GatewayResponse.approved("psp-ref-" + seq.incrementAndGet(),
                Duration.ofMillis(1));
        Runnable call = switch (op) {
            case "capture" -> () -> f.ops().capture(f.id());
            case "cancel" -> () -> f.ops().cancel(f.id());
            default -> () -> f.ops().refund(f.id(), Money.of(1_000, Currency.EUR));
        };

        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApplicationException.class, e -> {
            assertThat(e.code()).isEqualTo(ApplicationException.OPERATION_UNRECORDED);
            assertThat(e).isNotInstanceOf(ConcurrentPaymentModificationException.class);
            assertThat(e.getMessage()).contains("psp-ref-1").contains(f.id().toString());
        });

        assertThat(f.repo().saves).as("exactamente 5 intentos").hasValue(5);
        Payment after = reload(f.h(), f.id());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.refundedAmount()).isEqualTo(before.refundedAmount());
        assertThat(f.h().eventsOf(f.id(), PaymentEvent.class)).as("ningún evento de pago nuevo")
                .hasSize(eventsBefore);

        // El pago no ha quedado corrupto: sin sabotaje, la misma operación funciona.
        f.repo().failures = 0;
        call.run();
        assertThat(reload(f.h(), f.id()).version()).isEqualTo(before.version() + 1);
    }

    @RepeatedTest(5)
    @DisplayName("16 hilos con la misma clave de idempotencia: una sola llamada a la PSP; el resto IN_PROGRESS, replay o CONFLICT")
    void sameIdempotencyKeyConcurrently() throws Exception {
        RealAdaptersHarness h = new RealAdaptersHarness();
        AtomicInteger gatewayCalls = new AtomicInteger();
        h.gateway.onAuthorize = auth -> {
            gatewayCalls.incrementAndGet();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GatewayResponse.approved("auth-1", Duration.ofMillis(5));
        };
        List<String> names = new ArrayList<>();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            // La mitad con el mismo cuerpo y la otra mitad con otro importe.
            long amount = i % 2 == 0 ? 10_000 : 20_000;
            names.add(Long.toString(amount));
            tasks.add(() -> h.authorize("same-key", amount));
        }
        List<Outcome> outcomes = runAll(names, tasks);

        assertThat(gatewayCalls).hasValue(1);
        List<AuthorizationResult> fresh = outcomes.stream()
                .filter(o -> o.value() instanceof AuthorizationResult r && !r.replayed())
                .map(o -> (AuthorizationResult) o.value())
                .toList();
        assertThat(fresh).hasSize(1);
        long winnerAmount = fresh.getFirst().payment().amount().amountMinor();
        PaymentId winnerId = fresh.getFirst().payment().id();
        for (Outcome o : outcomes) {
            long amount = Long.parseLong(o.op());
            if (o.value() instanceof AuthorizationResult r) {
                assertThat(amount).isEqualTo(winnerAmount);
                assertThat(r.payment().id()).isEqualTo(winnerId);
                assertThat(r.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            } else {
                assertThat(o.value()).isInstanceOfSatisfying(ApplicationException.class, e -> {
                    String expected = amount == winnerAmount
                            ? ApplicationException.IDEMPOTENCY_IN_PROGRESS
                            : ApplicationException.IDEMPOTENCY_CONFLICT;
                    assertThat(e.code()).isEqualTo(expected);
                });
            }
        }
        assertThat(h.payments.size()).isEqualTo(1);
    }
}
