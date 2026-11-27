package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Autorizaciones simultáneas contra fakes seguros para hilos. Todas las esperas tienen
 * tiempo límite: si algo se interbloquea, el test falla con un volcado de hilos en lugar de
 * colgar la build.
 */
class AuthorizeConcurrencyTest {

    private static final int THREADS = 16;
    private static final long TIMEOUT_SECONDS = 20;

    private static final GatewayId A = GatewayId.of("gw-a");
    private static final GatewayId B = GatewayId.of("gw-b");
    private static final GatewayId C = GatewayId.of("gw-c");

    /** Lo que devuelve cada hilo: un resultado o la excepción que lanzó. */
    private record Outcome(AuthorizationResult result, Throwable error) {
    }

    private static List<Outcome> runConcurrently(int threads, IntFunction<Callable<AuthorizationResult>> task)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<AuthorizationResult> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(work.call(), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                try {
                    outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    throw new AssertionError("Posible interbloqueo: un hilo no ha terminado en " + TIMEOUT_SECONDS
                            + " s.\n" + threadDump());
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String threadDump() {
        var mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mx.findDeadlockedThreads();
        String header = deadlocked == null ? "Sin interbloqueo detectado por la JVM.\n"
                : "INTERBLOQUEO entre los hilos " + Arrays.toString(deadlocked) + "\n";
        return header + Arrays.stream(mx.dumpAllThreads(true, true)).map(ThreadInfo::toString)
                .collect(Collectors.joining());
    }

    private static String code(Throwable t) {
        return t instanceof ApplicationException a ? a.code() : t.toString();
    }

    // ---------------------------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("16 hilos con la MISMA clave y huella: un solo pago, una sola secuencia de llamadas; el resto IN_PROGRESS o replay")
    void sameKey() throws Exception {
        Harness h = new Harness(CircuitBreakerConfig.of(3, OPEN_DURATION), false);
        CountDownLatch othersDone = new CountDownLatch(THREADS - 1);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions, auth -> GatewayResponse.error("x", LATENCY));
        FunctionalGateway b = new FunctionalGateway(B, h.transactions, auth -> {
            // Mantiene la reserva en curso mientras el resto de hilos llega (con límite).
            try {
                othersDone.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GatewayResponse.approved("ref-b", LATENCY);
        });
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);

        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> {
            try {
                return h.authorize("same-key");
            } finally {
                othersDone.countDown();
            }
        });

        // Segunda oleada, ya completada la clave: todos replay.
        List<Outcome> replays = runConcurrently(THREADS, i -> () -> h.authorize("same-key"));

        assertThat(h.payments.size()).isEqualTo(1);
        assertThat(h.idempotency.reservations()).isEqualTo(1);
        assertThat(a.authorizeCalls()).isEqualTo(1);
        assertThat(b.authorizeCalls()).isEqualTo(1);
        PaymentId only = h.payments.all().getFirst().id();
        assertThat(a.authorizations()).allSatisfy(x -> assertThat(x.paymentId()).isEqualTo(only));

        List<Outcome> fresh = outcomes.stream().filter(o -> o.result() != null && !o.result().replayed()).toList();
        assertThat(fresh).singleElement().satisfies(o -> {
            assertThat(o.result().payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(o.result().attempts()).extracting(GatewayAttempt::gateway).containsExactly(A, B);
        });
        for (Outcome o : outcomes) {
            if (o.error() != null) {
                assertThat(code(o.error())).isEqualTo(ApplicationException.IDEMPOTENCY_IN_PROGRESS);
            } else if (o.result().replayed()) {
                assertThat(o.result().payment().id()).isEqualTo(only);
            }
        }
        assertThat(replays).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.result().replayed()).isTrue();
            assertThat(o.result().payment().id()).isEqualTo(only);
            assertThat(o.result().payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        });
        assertThat(a.authorizeCalls() + b.authorizeCalls()).isEqualTo(2);
        assertThat(h.events.published(PaymentCreated.class)).hasSize(1);
        assertThat(h.idempotency.isCompleted(Harness.request("same-key", 10_000).key())).isTrue();
        h.assertTransactionDiscipline();
    }

    @RepeatedTest(10)
    @DisplayName("16 hilos con claves distintas y todo fallando: 16 pagos FAILED y cada breaker cuenta exactamente 16 fallos")
    void distinctKeysCountersAddUp() throws Exception {
        Harness h = new Harness(CircuitBreakerConfig.of(1_000, OPEN_DURATION), false);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions, auth -> GatewayResponse.error("x", LATENCY));
        FunctionalGateway b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.timeout(LATENCY));
        FunctionalGateway c = new FunctionalGateway(C, h.transactions, auth -> {
            throw new IllegalStateException("caída");
        });
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b)
                .register(Harness.profile(C, 300), c);

        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> h.authorize("key-" + i));

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.result().payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(o.result().payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
            assertThat(o.result().attempts()).extracting(GatewayAttempt::gateway).containsExactly(A, B, C);
        });
        assertThat(h.payments.size()).isEqualTo(THREADS);
        assertThat(outcomes.stream().map(o -> o.result().payment().id()).distinct()).hasSize(THREADS);
        for (GatewayId id : List.of(A, B, C)) {
            assertThat(h.breakers.peek(id).state()).isEqualTo(CircuitState.CLOSED);
            assertThat(h.breakers.peek(id).snapshot().consecutiveFailures()).as("fallos de %s", id).isEqualTo(THREADS);
        }
        assertThat(a.authorizeCalls()).isEqualTo(THREADS);
        assertThat(b.authorizeCalls()).isEqualTo(THREADS);
        assertThat(c.authorizeCalls()).isEqualTo(THREADS);
        assertThat(h.events.published(GatewayAttemptRecorded.class)).hasSize(3 * THREADS);
        h.assertTransactionDiscipline();
    }

    @RepeatedTest(10)
    @DisplayName("16 hilos con claves distintas, A siempre ERROR y umbral 5: A se abre una vez, cuenta 5 fallos y los intentos cuadran con las llamadas")
    void distinctKeysBreakerOpensOnce() throws Exception {
        int threshold = 5;
        Harness h = new Harness(CircuitBreakerConfig.of(threshold, OPEN_DURATION), false);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions, auth -> GatewayResponse.error("x", LATENCY));
        FunctionalGateway b = new FunctionalGateway(B, h.transactions,
                auth -> GatewayResponse.approved("ref-" + auth.paymentId(), LATENCY));
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);

        // Además de autorizar, se consulta la salud a la vez para buscar interbloqueos.
        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> {
            if (i % 4 == 0) {
                h.health.health(h.clock.instant());
            }
            return h.authorize("key-" + i);
        });

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.result().payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(o.result().payment().authorizedBy()).contains(B);
        });
        assertThat(h.breakers.peek(A).state()).isEqualTo(CircuitState.OPEN);
        assertThat(h.breakers.peek(A).snapshot().consecutiveFailures()).isEqualTo(threshold);
        assertThat(h.events.published(CircuitOpened.class)).singleElement()
                .satisfies(e -> assertThat(e.gateway()).isEqualTo(A));
        long aAttempts = h.payments.all().stream().map(Payment::attempts).flatMap(List::stream)
                .filter(x -> x.gateway().equals(A)).count();
        assertThat(aAttempts).isEqualTo(a.authorizeCalls());
        assertThat(a.authorizeCalls()).isBetween(threshold, THREADS);
        assertThat(b.authorizeCalls()).isEqualTo(THREADS);
        h.assertTransactionDiscipline();
    }

    @RepeatedTest(5)
    @DisplayName("HALF_OPEN con una sola prueba y 16 hilos a la vez: exactamente un hilo llama a la pasarela en prueba")
    void singleTrialUnderConcurrency() throws Exception {
        Harness h = new Harness(CircuitBreakerConfig.of(1, OPEN_DURATION), false);
        CountDownLatch othersDone = new CountDownLatch(THREADS - 1);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions, auth -> {
            // El hilo con la prueba la retiene hasta que el resto ha terminado (con límite).
            try {
                othersDone.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GatewayResponse.approved("ref-a", LATENCY);
        });
        FunctionalGateway b = new FunctionalGateway(B, h.transactions,
                auth -> GatewayResponse.approved("ref-" + auth.paymentId(), LATENCY));
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
        h.breakers.peek(A).recordFailure(h.clock.instant());
        h.breakers.peek(A).pullEvents();
        h.clock.advance(OPEN_DURATION);

        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> {
            boolean callsA = false;
            try {
                AuthorizationResult r = h.authorize("key-" + i);
                callsA = r.attempts().stream().anyMatch(x -> x.gateway().equals(A));
                return r;
            } finally {
                if (!callsA) {
                    othersDone.countDown();
                }
            }
        });

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(a.authorizeCalls()).isEqualTo(1);
        assertThat(b.authorizeCalls()).isEqualTo(THREADS - 1);
        assertThat(outcomes).filteredOn(o -> o.result().payment().authorizedBy().orElseThrow().equals(A)).hasSize(1);
        assertThat(h.breakers.peek(A).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(1);
        assertThat(h.events.published(CircuitClosed.class)).hasSize(1);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.result().attempts())
                .allSatisfy(x -> assertThat(x.outcome()).isEqualTo(AttemptOutcome.APPROVED)));
        h.assertTransactionDiscipline();
    }

    @Test
    @DisplayName("autorizaciones, capturas y consultas de salud mezcladas sobre los mismos breakers no se interbloquean")
    void mixedWorkloadDoesNotDeadlock() throws Exception {
        Harness h = new Harness(CircuitBreakerConfig.of(3, OPEN_DURATION), false);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions,
                auth -> auth.paymentId().value().getLeastSignificantBits() % 2 == 0
                        ? GatewayResponse.error("x", LATENCY)
                        : GatewayResponse.approved("a-" + auth.paymentId(), LATENCY))
                .onOperation(op -> GatewayResponse.approved("cap", LATENCY));
        FunctionalGateway b = new FunctionalGateway(B, h.transactions,
                auth -> GatewayResponse.approved("b-" + auth.paymentId(), LATENCY))
                .onOperation(op -> GatewayResponse.error("x", LATENCY));
        FunctionalGateway c = new FunctionalGateway(C, h.transactions,
                auth -> GatewayResponse.timeout(LATENCY));
        h.catalog.register(Harness.profile(C, 50), c).register(Harness.profile(A, 100), a)
                .register(Harness.profile(B, 200), b);

        List<Outcome> first = runConcurrently(THREADS, i -> () -> h.authorize("key-" + i));
        List<PaymentId> ids = first.stream().map(o -> o.result().payment().id()).toList();

        List<Outcome> mixed = runConcurrently(THREADS * 2, i -> () -> {
            switch (i % 3) {
                case 0 -> {
                    try {
                        h.operations.capture(ids.get(i % ids.size()));
                    } catch (ApplicationException | dev.pedrogomez.payaf.domain.shared.DomainException expected) {
                        // GATEWAY_OPERATION_FAILED en B o una transición ilegal si ya está capturado.
                    }
                    return null;
                }
                case 1 -> {
                    h.health.health(h.clock.instant());
                    return null;
                }
                default -> {
                    return h.authorize("mixed-" + i);
                }
            }
        });

        assertThat(mixed).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(h.payments.size()).isEqualTo(THREADS + (int) java.util.stream.IntStream.range(0, THREADS * 2)
                .filter(i -> i % 3 == 2).count());
        h.assertTransactionDiscipline();
    }
}
