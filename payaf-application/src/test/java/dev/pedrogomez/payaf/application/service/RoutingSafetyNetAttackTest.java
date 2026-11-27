package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.fake.FlakyClock;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.fake.MutableClock;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Ataques a la red de seguridad de {@code AuthorizePaymentService.route}: fallos del almacén
 * de breakers, del catálogo, de la política y del reloj en cada punto del enrutado, y
 * {@link Error} que deben propagarse sin dejar nada publicado a medias.
 */
class RoutingSafetyNetAttackTest {

    private static final GatewayId A = GatewayId.of("gw-a");
    private static final GatewayId B = GatewayId.of("gw-b");
    private static final int THRESHOLD = 3;

    private Harness h;
    private FunctionalGateway a;
    private FunctionalGateway b;
    /** Se pone a true cuando A ha respondido: arma el fallo «justo después» de su respuesta. */
    private final AtomicBoolean aAnswered = new AtomicBoolean();

    private void twoGateways(AttemptOutcome aOutcome, java.time.Clock serviceClock) {
        h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false, serviceClock);
        a = new FunctionalGateway(A, h.transactions, auth -> {
            aAnswered.set(true);
            return switch (aOutcome) {
                case APPROVED -> GatewayResponse.approved("ref-a", LATENCY);
                case DECLINED -> GatewayResponse.declined("refused", LATENCY);
                case ERROR -> GatewayResponse.error("http_500", LATENCY);
                case TIMEOUT -> GatewayResponse.timeout(LATENCY);
            };
        });
        b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY));
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
    }

    private void twoGateways(AttemptOutcome aOutcome) {
        twoGateways(aOutcome, null);
    }

    /** Deja A en OPEN con el tiempo de apertura ya vencido (la próxima decisión la pasa a HALF_OPEN). */
    private void openAAndLetItExpire() {
        CircuitBreaker breaker = h.breakers.peek(A);
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure(h.clock.instant());
        }
        breaker.pullEvents();
        h.clock.advance(OPEN_DURATION);
    }

    private static IllegalStateException storeDown() {
        return new IllegalStateException("almacén de breakers caído");
    }

    /**
     * Lo que la red de seguridad debe garantizar siempre que no haya un Error: el pago cerrado
     * (AUTHORIZED o FAILED), guardado como se devuelve, la clave completada, una transacción de
     * cierre, los eventos una sola vez y ningún evento de breaker sin publicar.
     */
    private void assertClosedCleanly(PaymentRequest request, AuthorizationResult result) {
        assertThat(result.payment().status()).isIn(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
        Payment stored = h.payments.stored(result.payment().id());
        assertThat(stored.status()).isEqualTo(result.payment().status());
        assertThat(stored.attempts()).isEqualTo(result.attempts());
        assertThat(h.idempotency.isCompleted(request.key())).isTrue();
        h.assertTransactionDiscipline();
        assertThat(h.transactions.transactionCount()).isEqualTo(2);
        AuthorizePaymentSpecTest.assertNoDuplicateInstances(h.events.published());
        assertThat(h.events.published(PaymentCreated.class)).hasSize(1);
        assertThat(h.events.published(PaymentAuthorized.class).size() + h.events.published(PaymentFailed.class).size())
                .isEqualTo(1);
        assertThat(h.events.published(GatewayAttemptRecorded.class)).hasSize(result.attempts().size());
        for (GatewayId id : List.of(A, B)) {
            assertThat(h.breakers.peek(id).pullEvents()).as("eventos de %s sin publicar", id).isEmpty();
        }
        if (result.payment().status() == PaymentStatus.AUTHORIZED) {
            GatewayAttempt last = result.attempts().getLast();
            assertThat(last.outcome()).isEqualTo(AttemptOutcome.APPROVED);
            assertThat(result.payment().authorizedBy()).contains(last.gateway());
        }
        AuthorizationResult replay = h.authorize(request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment()).isEqualTo(result.payment());
    }

    private void assertRoutingError(AuthorizationResult result) {
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.ROUTING_ERROR);
    }

    // ---------------------------------------------------------------------------------
    // (1) El almacén de breakers falla justo después de la respuesta
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("(1) CircuitBreakerStore.save lanza justo después de la respuesta de la pasarela")
    class SaveFailsAfterResponse {

        @Test
        @DisplayName("tras un APPROVED con el circuito CLOSED: AUTHORIZED, clave completada y eventos intactos")
        void afterApprovedClosed() {
            twoGateways(AttemptOutcome.APPROVED);
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.payment().authorizedBy()).contains(A);
            assertThat(result.payment().gatewayReference()).contains("ref-a");
            assertThat(b.authorizeCalls()).isZero();
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("tras el APPROVED de la prueba de HALF_OPEN: AUTHORIZED, el circuito se cierra una vez y no se reabre")
        void afterApprovedHalfOpen() {
            twoGateways(AttemptOutcome.APPROVED);
            openAAndLetItExpire();
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertThat(result.payment().authorizedBy()).contains(A);
            CircuitBreaker breaker = h.breakers.peek(A);
            assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
            assertThat(breaker.snapshot().trialsInFlight()).isZero();
            assertThat(breaker.snapshot().consecutiveFailures()).isZero();
            assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(1);
            assertThat(h.events.published(CircuitClosed.class)).hasSize(1);
            assertThat(h.events.published(CircuitOpened.class)).isEmpty();
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("tras un DECLINED: FAILED con DECLINED (no ROUTING_ERROR) y sin failover")
        void afterDeclined() {
            twoGateways(AttemptOutcome.DECLINED);
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertThat(result.payment().failureReason()).contains("DECLINED: refused");
            assertThat(b.authorizeCalls()).isZero();
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("tras un ERROR: ROUTING_ERROR y el fallo se cuenta UNA vez en el breaker (la red no lo duplica)")
        void afterError() {
            twoGateways(AttemptOutcome.ERROR);
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(result.attempts()).extracting(GatewayAttempt::gateway).containsExactly(A);
            assertThat(h.breakers.peek(A).snapshot().consecutiveFailures()).isEqualTo(1);
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("tras el ERROR de la prueba de HALF_OPEN: el circuito se reabre una sola vez y no queda prueba en vuelo")
        void afterErrorHalfOpen() {
            twoGateways(AttemptOutcome.ERROR);
            openAAndLetItExpire();
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(h.breakers.peek(A).state()).isEqualTo(CircuitState.OPEN);
            assertThat(h.breakers.peek(A).snapshot().trialsInFlight()).isZero();
            assertThat(h.events.published(CircuitOpened.class)).hasSize(1);
            assertClosedCleanly(request, result);
        }
    }

    // ---------------------------------------------------------------------------------
    // (2) Excepciones en cada punto del bucle y en decide
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("(2) excepciones en store.get, catalog.gateway y decide")
    class ExceptionsInTheLoop {

        @Test
        @DisplayName("store.get falla en la segunda pasarela: ROUTING_ERROR sin llamar a nadie y decisión vacía")
        void storeGetFails() {
            twoGateways(AttemptOutcome.APPROVED);
            h.breakers.failGetWhen(id -> id.equals(B) ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(result.attempts()).isEmpty();
            assertThat(result.decision().candidates()).isEmpty();
            assertThat(a.authorizeCalls() + b.authorizeCalls()).isZero();
            h.breakers.failGetWhen(id -> null);
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("catalog.gateway falla en la segunda candidata tras un ERROR: el intento y su fallo quedan, ROUTING_ERROR")
        void catalogLookupFailsOnSecondCandidate() {
            twoGateways(AttemptOutcome.ERROR);
            h.catalog.onNextLookup(B, () -> {
                throw new IllegalStateException("catálogo caído");
            });
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(A, AttemptOutcome.ERROR));
            assertThat(result.decision().candidates()).hasSize(2);
            assertThat(h.breakers.peek(A).snapshot().consecutiveFailures()).isEqualTo(1);
            assertThat(b.authorizeCalls()).isZero();
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("catalog.gateway falla para una candidata en HALF_OPEN: su prueba no se gasta ni se cuenta como fallo")
        void catalogLookupFailsBeforeAcquiringTrial() {
            twoGateways(AttemptOutcome.APPROVED);
            openAAndLetItExpire();
            h.catalog.onNextLookup(A, () -> {
                throw new IllegalStateException("catálogo caído");
            });
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            CircuitBreaker breaker = h.breakers.peek(A);
            assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(breaker.snapshot().trialsInFlight()).isZero();
            assertThat(breaker.snapshot().consecutiveFailures()).isEqualTo(THRESHOLD);
            assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(1);
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("decide falla con dos perfiles con el mismo id: ROUTING_ERROR, nadie llamado")
        void decideFailsWithDuplicateProfile() {
            twoGateways(AttemptOutcome.APPROVED);
            h.catalog.registerProfileOnly(Harness.profile(A, 999));
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(result.decision().candidates()).isEmpty();
            assertThat(a.authorizeCalls() + b.authorizeCalls()).isZero();
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("decide falla al guardar el paso OPEN→HALF_OPEN: ROUTING_ERROR y el CircuitHalfOpened se publica igualmente")
        void decideFailsSavingHalfOpen() {
            twoGateways(AttemptOutcome.APPROVED);
            openAAndLetItExpire();
            h.breakers.failSaveWhen(br -> br.gateway().equals(A) && br.state() == CircuitState.HALF_OPEN
                    && !aAnswered.get() ? storeDown() : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertRoutingError(result);
            assertThat(a.authorizeCalls() + b.authorizeCalls()).isZero();
            assertThat(h.breakers.peek(A).snapshot().trialsInFlight()).isZero();
            assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(1);
            h.breakers.failSaveWhen(br -> null);
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("la excepción comprobada lanzada a escondidas por el adaptador es ERROR ADAPTER_EXCEPTION con failover")
        void sneakyCheckedException() {
            h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false);
            a = new FunctionalGateway(A, h.transactions, auth -> {
                throw FunctionalGateway.sneakyThrow(new java.io.IOException("reset"));
            });
            b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY));
            h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
            PaymentRequest request = Harness.request("k1", 10_000);

            AuthorizationResult result = h.authorize(request);

            assertThat(result.attempts()).extracting(GatewayAttempt::code)
                    .containsExactly(java.util.Optional.of(ServiceSupport.ADAPTER_EXCEPTION), java.util.Optional.empty());
            assertThat(result.payment().authorizedBy()).contains(B);
            assertClosedCleanly(request, result);
        }
    }

    // ---------------------------------------------------------------------------------
    // (2 bis) Barrido: el reloj falla una vez en cada lectura posible del enrutado
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("(2) barrido: el reloj falla en cada una de sus lecturas (una vez o desde ahí en adelante), solo o con store.save")
    class ClockSweep {

        private record Run(int failAt, Throwable escaped, AuthorizationResult result, int trialsLeft,
                           boolean approvedAtPsp) {
        }

        /** Se pone a true cuando cualquier pasarela ha respondido: arma el fallo de store.save. */
        private final AtomicBoolean anyAnswered = new AtomicBoolean();

        private void scenario(AttemptOutcome aOutcome, java.time.Clock serviceClock, boolean saveFailsAfterCalls) {
            aAnswered.set(false);
            anyAnswered.set(false);
            h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false, serviceClock);
            a = new FunctionalGateway(A, h.transactions, auth -> {
                aAnswered.set(true);
                anyAnswered.set(true);
                return switch (aOutcome) {
                    case APPROVED -> GatewayResponse.approved("ref-a", LATENCY);
                    case DECLINED -> GatewayResponse.declined("refused", LATENCY);
                    case ERROR -> GatewayResponse.error("http_500", LATENCY);
                    case TIMEOUT -> GatewayResponse.timeout(LATENCY);
                };
            });
            b = new FunctionalGateway(B, h.transactions, auth -> {
                anyAnswered.set(true);
                return GatewayResponse.approved("ref-b", LATENCY);
            });
            h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
            if (saveFailsAfterCalls) {
                h.breakers.failSaveWhen(br -> anyAnswered.get() ? storeDown() : null);
            }
        }

        private void halfOpenA(MutableClock base) {
            CircuitBreaker breaker = h.breakers.peek(A);
            for (int i = 0; i < THRESHOLD; i++) {
                breaker.recordFailure(base.instant());
            }
            breaker.pullEvents();
            base.advance(OPEN_DURATION);
            h.clock.advance(OPEN_DURATION);
        }

        private Run runFailingAt(int failAt, ClockMode mode, AttemptOutcome aOutcome, boolean halfOpen,
                                 boolean saveFails) {
            MutableClock base = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
            FlakyClock flaky = new FlakyClock(base);
            scenario(aOutcome, flaky, saveFails);
            if (halfOpen) {
                halfOpenA(base);
            }
            IllegalStateException failure = new IllegalStateException("reloj caído en la lectura " + failAt);
            if (mode == ClockMode.ONCE) {
                flaky.failOnCall(failAt, failure);
            } else {
                flaky.failFromCall(failAt, failure);
            }
            Throwable escaped = null;
            AuthorizationResult result = null;
            try {
                result = h.authorize(Harness.request("k" + failAt, 10_000));
            } catch (Throwable t) {
                escaped = t;
            }
            flaky.heal();
            h.breakers.failSaveWhen(br -> null);
            boolean approvedAtPsp = (aOutcome == AttemptOutcome.APPROVED && a.authorizeCalls() > 0)
                    || b.authorizeCalls() > 0;
            return new Run(failAt, escaped, result, h.breakers.peek(A).snapshot().trialsInFlight(), approvedAtPsp);
        }

        private int clockReadsOfANormalRun(AttemptOutcome aOutcome, boolean halfOpen) {
            MutableClock base = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
            FlakyClock counting = new FlakyClock(base);
            scenario(aOutcome, counting, false);
            if (halfOpen) {
                halfOpenA(base);
            }
            int before = counting.calls();
            h.authorize(Harness.request("count", 10_000));
            return counting.calls() - before;
        }

        private void sweep(AttemptOutcome aOutcome, boolean halfOpen, ClockMode mode, boolean saveFails) {
            int reads = clockReadsOfANormalRun(aOutcome, halfOpen);
            assertThat(reads).isGreaterThan(5);
            List<String> problems = new ArrayList<>();
            for (int n = 1; n <= reads + 1; n++) {
                Run run = runFailingAt(n, mode, aOutcome, halfOpen, saveFails);
                PaymentRequest request = Harness.request("k" + n, 10_000);
                if (n == 1) {
                    // La primera lectura es la de la reserva, antes de abrir la transacción 1:
                    // sale la excepción y no queda nada creado ni reservado.
                    if (run.escaped() == null || h.payments.size() != 0 || h.idempotency.isReserved(request.key())) {
                        problems.add("n=1: esperaba que fallara la reserva sin dejar nada");
                    }
                    continue;
                }
                if (run.escaped() != null) {
                    if (run.escaped() instanceof ApplicationException ae
                            && ae.code().equals(ApplicationException.AUTHORIZATION_UNRECORDED)) {
                        Payment stored = h.payments.all().getFirst();
                        if (stored.status() != PaymentStatus.PENDING || h.idempotency.isCompleted(request.key())) {
                            problems.add("n=" + n + ": AUTHORIZATION_UNRECORDED pero el pago está " + stored.status());
                        }
                        problems.add("n=" + n + ": (informativo) se ha alcanzado AUTHORIZATION_UNRECORDED");
                    } else {
                        problems.add("n=" + n + ": se escapa " + run.escaped());
                    }
                    continue;
                }
                try {
                    assertClosedCleanly(request, run.result());
                } catch (AssertionError e) {
                    problems.add("n=" + n + ": " + e.getMessage().lines().filter(l -> !l.isBlank()).findFirst()
                            .orElse(""));
                }
                if (run.trialsLeft() != 0) {
                    problems.add("n=" + n + ": queda una prueba de HALF_OPEN en vuelo en A sin resultado");
                }
                if (run.approvedAtPsp() && run.result().payment().status() != PaymentStatus.AUTHORIZED) {
                    problems.add("n=" + n + ": la PSP aprobó pero el pago acaba " + run.result().payment().status()
                            + " " + run.result().payment().failureReason().orElse("")
                            + " (autorización huérfana en la PSP)");
                }
            }
            assertThat(problems).as("barrido %s halfOpen=%s reloj=%s saveFalla=%s, %d lecturas", aOutcome, halfOpen,
                    mode, saveFails, reads).isEmpty();
        }

        @ParameterizedTest(name = "A {0}, HALF_OPEN={1}, reloj {2}, save falla={3}")
        @MethodSource("dev.pedrogomez.payaf.application.service.RoutingSafetyNetAttackTest#sweeps")
        @DisplayName("ningún fallo del reloj ni del almacén deja un pago aprobado por la PSP en FAILED ni la clave en curso")
        void sweepAll(AttemptOutcome aOutcome, boolean halfOpen, ClockMode mode, boolean saveFails) {
            sweep(aOutcome, halfOpen, mode, saveFails);
        }
    }

    /** Cómo falla el reloj a partir de la lectura N. */
    enum ClockMode { ONCE, FROM_THERE_ON }

    static Stream<Arguments> sweeps() {
        List<Arguments> all = new ArrayList<>();
        for (AttemptOutcome outcome : AttemptOutcome.values()) {
            for (boolean halfOpen : List.of(false, true)) {
                for (ClockMode mode : ClockMode.values()) {
                    for (boolean saveFails : List.of(false, true)) {
                        all.add(Arguments.of(outcome, halfOpen, mode, saveFails));
                    }
                }
            }
        }
        return all.stream();
    }

    // ---------------------------------------------------------------------------------
    // (d) El flag de interrupción
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("(d) interrupciones")
    class Interruption {

        @Test
        @DisplayName("un adaptador que lanza InterruptedException: ERROR ADAPTER_EXCEPTION, failover y el hilo queda interrumpido")
        void interruptedExceptionRestoresFlag() {
            h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false);
            a = new FunctionalGateway(A, h.transactions, auth -> {
                throw FunctionalGateway.sneakyThrow(new InterruptedException("apagado"));
            });
            b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY));
            h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
            PaymentRequest request = Harness.request("k1", 10_000);
            Thread.interrupted();

            AuthorizationResult result;
            boolean interrupted;
            try {
                result = h.authorize(request);
            } finally {
                interrupted = Thread.interrupted(); // lo lee y lo limpia para no contaminar otros tests
            }

            assertThat(interrupted).as("el flag de interrupción debe restaurarse").isTrue();
            assertThat(result.attempts()).extracting(GatewayAttempt::code)
                    .containsExactly(java.util.Optional.of(ServiceSupport.ADAPTER_EXCEPTION), java.util.Optional.empty());
            assertThat(result.payment().authorizedBy()).contains(B);
            assertClosedCleanly(request, result);
        }

        @Test
        @DisplayName("un adaptador que lanza otra excepción no marca el hilo como interrumpido")
        void otherExceptionsDoNotInterrupt() {
            h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false);
            a = new FunctionalGateway(A, h.transactions, auth -> {
                throw new IllegalStateException("x");
            });
            b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY));
            h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
            Thread.interrupted();

            h.authorize(Harness.request("k1", 10_000));

            assertThat(Thread.interrupted()).isFalse();
        }
    }

    // ---------------------------------------------------------------------------------
    // (4) Los Error se propagan y no dejan nada a medias
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("(4) un Error se propaga y no se publica nada a medias")
    class Errors {

        private void assertNothingAfterTransactionOne(PaymentRequest request) {
            assertThat(h.transactions.transactionCount()).isEqualTo(1);
            assertThat(h.events.published()).singleElement().isInstanceOf(PaymentCreated.class);
            assertThat(h.payments.all()).singleElement()
                    .satisfies(p -> assertThat(p.status()).isEqualTo(PaymentStatus.PENDING));
            assertThat(h.idempotency.isReserved(request.key())).isTrue();
            assertThat(h.idempotency.isCompleted(request.key())).isFalse();
            h.assertTransactionDiscipline();
        }

        @Test
        @DisplayName("Error del adaptador: sale la misma instancia, B no se llama y no hay transacción 2")
        void errorFromAdapter() {
            StackOverflowError boom = new StackOverflowError("adaptador");
            h = new Harness(CircuitBreakerConfig.of(THRESHOLD, OPEN_DURATION), false);
            a = new FunctionalGateway(A, h.transactions, auth -> {
                throw boom;
            });
            b = new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY));
            h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
            PaymentRequest request = Harness.request("k1", 10_000);

            assertThat(catchThrowable(() -> h.authorize(request))).isSameAs(boom);
            assertThat(b.authorizeCalls()).isZero();
            assertNothingAfterTransactionOne(request);
        }

        @Test
        @DisplayName("Error del almacén de breakers tras un APPROVED: se propaga y no hay transacción 2")
        void errorFromStoreAfterApproved() {
            twoGateways(AttemptOutcome.APPROVED);
            OutOfMemoryError oom = new OutOfMemoryError("simulado");
            h.breakers.failSaveWhen(br -> aAnswered.getAndSet(false) ? oom : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            assertThat(catchThrowable(() -> h.authorize(request))).isSameAs(oom);
            assertNothingAfterTransactionOne(request);
        }

        @Test
        @DisplayName("Error en decide (almacén al guardar HALF_OPEN): se propaga sin llamar a nadie")
        void errorInDecide() {
            twoGateways(AttemptOutcome.APPROVED);
            openAAndLetItExpire();
            LinkageError linkage = new LinkageError("clase rota");
            h.breakers.failSaveWhen(br -> br.state() == CircuitState.HALF_OPEN ? linkage : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            assertThat(catchThrowable(() -> h.authorize(request))).isSameAs(linkage);
            assertThat(a.authorizeCalls() + b.authorizeCalls()).isZero();
            assertNothingAfterTransactionOne(request);
        }

        @Test
        @DisplayName("Error al publicar en la transacción 2: rollback completo (pago PENDING, sin eventos nuevos, clave sin completar)")
        void errorInsideSecondTransaction() {
            twoGateways(AttemptOutcome.APPROVED);
            AssertionError broken = new AssertionError("outbox roto");
            h.events.failWhen(events -> events.stream().anyMatch(PaymentAuthorized.class::isInstance) ? broken : null);
            PaymentRequest request = Harness.request("k1", 10_000);

            assertThatThrownBy(() -> h.authorize(request)).isSameAs(broken);
            assertThat(h.transactions.transactionCount()).isEqualTo(2);
            assertThat(h.transactions.rollbackCount()).isEqualTo(1);
            assertThat(h.events.published()).singleElement().isInstanceOf(PaymentCreated.class);
            assertThat(h.payments.all()).singleElement()
                    .satisfies(p -> assertThat(p.status()).isEqualTo(PaymentStatus.PENDING));
            assertThat(h.idempotency.isCompleted(request.key())).isFalse();
            h.assertTransactionDiscipline();
        }
    }
}
