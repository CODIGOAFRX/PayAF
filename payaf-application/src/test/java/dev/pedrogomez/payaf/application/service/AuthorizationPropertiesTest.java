package dev.pedrogomez.payaf.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.routing.RoutingCandidate;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Propiedades del enrutado con failover: escenarios aleatorios de 3 o 4 pasarelas con
 * perfiles válidos, respuestas aleatorias y breakers en estados variados. No se recalcula el
 * resultado esperado (eso sería repetir la implementación): se comprueban los invariantes que
 * la especificación exige a cualquier ejecución.
 */
class AuthorizationPropertiesTest {

    private static final Duration OPEN = Duration.ofSeconds(30);

    enum Behaviour { APPROVED, DECLINED, ERROR, TIMEOUT, EXCEPTION }

    enum BreakerSetup { CLOSED, CLOSED_WITH_FAILURES, OPEN, OPEN_EXPIRED, HALF_OPEN_TRIAL_HELD, HALF_OPEN_TRIAL_EXPIRED }

    record GatewaySpec(int feeBps, long fixedFee, Set<Currency> currencies, Set<CountryCode> countries,
                       boolean enabled, Behaviour behaviour, BreakerSetup breaker, boolean hasAdapter) {
    }

    record Scenario(List<GatewaySpec> gateways, int threshold, long amountMinor) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<GatewaySpec> gateway = Combinators.combine(
                Arbitraries.integers().between(0, 10_000),
                Arbitraries.longs().between(0, 500),
                Arbitraries.of(Set.of(Currency.EUR), Set.of(Currency.EUR, Currency.USD), Set.of(Currency.USD)),
                Arbitraries.of(Set.<CountryCode>of(), Set.of(CountryCode.of("ES")), Set.of(CountryCode.of("FR"))),
                Arbitraries.of(true, true, true, false),
                Arbitraries.of(Behaviour.class),
                Arbitraries.of(BreakerSetup.class),
                Arbitraries.of(true, true, true, true, false))
                .as((fee, fixed, cur, countries, enabled, behaviour, breaker, adapter) -> new GatewaySpec(fee,
                        cur.contains(Currency.EUR) ? fixed : 0, cur, countries, enabled, behaviour, breaker, adapter));
        return Combinators.combine(
                gateway.list().ofMinSize(3).ofMaxSize(4),
                Arbitraries.integers().between(1, 3),
                Arbitraries.longs().between(1, 1_000_000_000_000L))
                .as(Scenario::new);
    }

    @Property(tries = 400)
    void failoverInvariants(@ForAll("scenarios") Scenario s) {
        Harness h = new Harness(CircuitBreakerConfig.of(s.threshold(), OPEN), false);
        Instant now = h.clock.instant();
        Map<GatewayId, FunctionalGateway> adapters = new LinkedHashMap<>();
        Map<GatewayId, BreakerSetup> setups = new HashMap<>();
        Map<GatewayId, CircuitBreakerSnapshot> before = new HashMap<>();
        Map<GatewayId, CircuitState> effectiveBefore = new HashMap<>();

        for (int i = 0; i < s.gateways().size(); i++) {
            GatewaySpec spec = s.gateways().get(i);
            GatewayId id = GatewayId.of("gw-" + i);
            Map<Currency, Long> fixed = spec.fixedFee() > 0 ? Map.of(Currency.EUR, spec.fixedFee()) : Map.of();
            GatewayProfile profile = new GatewayProfile(id, spec.currencies(), spec.countries(), spec.feeBps(), fixed,
                    spec.enabled());
            if (spec.hasAdapter()) {
                FunctionalGateway adapter = new FunctionalGateway(id, h.transactions, a -> respond(spec.behaviour(), id));
                adapters.put(id, adapter);
                h.catalog.register(profile, adapter);
            } else {
                h.catalog.registerProfileOnly(profile);
            }
            CircuitBreaker breaker = h.breakers.peek(id);
            prepare(breaker, spec.breaker(), s.threshold(), now);
            breaker.pullEvents();
            setups.put(id, spec.breaker());
            before.put(id, breaker.snapshot());
            effectiveBefore.put(id, effectiveState(spec.breaker(), s.threshold()));
        }

        PaymentRequest request = Harness.request("prop", s.amountMinor());
        AuthorizationResult result = h.authorize(request);
        List<GatewayAttempt> attempts = result.attempts();
        List<GatewayId> attempted = attempts.stream().map(GatewayAttempt::gateway).toList();
        List<GatewayId> candidates = result.decision().candidates().stream().map(RoutingCandidate::gateway).toList();

        // (a) AUTHORIZED solo si algún intento salió APPROVED, y ese intento es el último.
        boolean anyApproved = attempts.stream().anyMatch(a -> a.outcome() == AttemptOutcome.APPROVED);
        assertThat(result.payment().status() == PaymentStatus.AUTHORIZED).isEqualTo(anyApproved);
        if (anyApproved) {
            GatewayAttempt last = attempts.getLast();
            assertThat(last.outcome()).isEqualTo(AttemptOutcome.APPROVED);
            assertThat(result.payment().authorizedBy()).contains(last.gateway());
            assertThat(result.payment().gatewayReference()).isEqualTo(last.gatewayReference());
        } else {
            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.payment().authorizedBy()).isEmpty();
        }

        // (b) Nunca hay un intento después de un DECLINED o un APPROVED.
        for (int i = 0; i < attempts.size() - 1; i++) {
            assertThat(attempts.get(i).outcome()).isIn(AttemptOutcome.ERROR, AttemptOutcome.TIMEOUT);
        }
        if (!attempts.isEmpty() && attempts.getLast().outcome() == AttemptOutcome.DECLINED) {
            assertThat(result.payment().failureReason()).hasValueSatisfying(r -> assertThat(r).startsWith("DECLINED"));
        }

        // (c) Los intentos siguen un subconjunto ordenado de las candidatas; además, en un solo
        //     hilo, es justo el prefijo de las candidatas con adaptador (no se salta ninguna).
        assertThat(isOrderedSubsequence(attempted, candidates)).as("%s ⊑ %s", attempted, candidates).isTrue();
        List<GatewayId> withAdapter = candidates.stream().filter(adapters::containsKey).toList();
        assertThat(withAdapter.subList(0, attempted.size())).isEqualTo(attempted);
        boolean terminal = !attempts.isEmpty() && !attempts.getLast().outcome().isTechnicalFailure();
        if (!terminal) {
            // Failover agotado: se han probado todas las candidatas con adaptador.
            assertThat(attempted).isEqualTo(withAdapter);
            assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
        }

        // (d) Cada pasarela se llama como mucho una vez; las llamadas son exactamente los intentos.
        assertThat(new HashSet<>(attempted)).hasSameSizeAs(attempted);
        adapters.forEach((id, adapter) ->
                assertThat(adapter.authorizeCalls()).as("llamadas a %s", id).isEqualTo(attempted.contains(id) ? 1 : 0));
        // Nunca se llama a una pasarela con el circuito cerrado al tráfico.
        for (GatewayId id : attempted) {
            assertThat(setups.get(id)).isNotIn(BreakerSetup.OPEN, BreakerSetup.HALF_OPEN_TRIAL_HELD);
        }

        // (e) Ninguna llamada con una transacción abierta ni persistencia fuera de ellas.
        h.assertTransactionDiscipline();
        assertThat(h.transactions.transactionCount()).isEqualTo(2);

        // (f) La idempotencia queda completada y un replay no vuelve a llamar a nadie.
        assertThat(h.idempotency.isCompleted(request.key())).isTrue();
        AuthorizationResult replay = h.authorize(request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment()).isEqualTo(result.payment());
        adapters.forEach((id, adapter) ->
                assertThat(adapter.authorizeCalls()).isEqualTo(attempted.contains(id) ? 1 : 0));

        // Los breakers registran cada resultado una vez.
        for (GatewayAttempt attempt : attempts) {
            CircuitBreakerSnapshot after = h.breakers.peek(attempt.gateway()).snapshot();
            CircuitBreakerSnapshot was = before.get(attempt.gateway());
            CircuitState wasEffective = effectiveBefore.get(attempt.gateway());
            if (attempt.outcome().isTechnicalFailure()) {
                if (wasEffective == CircuitState.HALF_OPEN) {
                    assertThat(after.state()).isEqualTo(CircuitState.OPEN);
                } else {
                    int expected = was.consecutiveFailures() + 1;
                    assertThat(after.consecutiveFailures()).isEqualTo(expected);
                    assertThat(after.state()).isEqualTo(expected >= s.threshold() ? CircuitState.OPEN : CircuitState.CLOSED);
                }
            } else {
                assertThat(after.state()).isEqualTo(CircuitState.CLOSED);
                assertThat(after.consecutiveFailures()).isZero();
            }
        }
        // Las pasarelas no intentadas no cambian de contadores.
        for (GatewayId id : before.keySet()) {
            if (!attempted.contains(id)) {
                assertThat(h.breakers.peek(id).snapshot().consecutiveFailures())
                        .isEqualTo(before.get(id).consecutiveFailures());
            }
        }

        // Eventos: los del pago una vez, los de los breakers publicados y ninguno pendiente.
        List<DomainEvent> published = h.events.published();
        assertThat(h.events.published(PaymentCreated.class)).hasSize(1);
        assertThat(h.events.published(GatewayAttemptRecorded.class)).hasSize(attempts.size());
        assertThat(h.events.published(PaymentAuthorized.class).size() + h.events.published(PaymentFailed.class).size())
                .isEqualTo(1);
        AuthorizePaymentSpecTest.assertNoDuplicateInstances(published);
        for (GatewayId id : before.keySet()) {
            assertThat(h.breakers.peek(id).pullEvents()).as("eventos sin publicar de %s", id).isEmpty();
        }
    }

    private static GatewayResponse respond(Behaviour behaviour, GatewayId id) {
        return switch (behaviour) {
            case APPROVED -> GatewayResponse.approved("ref-" + id, Duration.ofMillis(10));
            case DECLINED -> GatewayResponse.declined("refused", Duration.ofMillis(10));
            case ERROR -> GatewayResponse.error("http_503", Duration.ofMillis(10));
            case TIMEOUT -> GatewayResponse.timeout(Duration.ofSeconds(3));
            case EXCEPTION -> throw new IllegalStateException("adaptador roto");
        };
    }

    /** Deja el breaker en el estado pedido usando solo instantes anteriores a {@code now}. */
    private static void prepare(CircuitBreaker breaker, BreakerSetup setup, int threshold, Instant now) {
        switch (setup) {
            case CLOSED -> {
            }
            case CLOSED_WITH_FAILURES -> {
                for (int i = 0; i < threshold - 1; i++) {
                    breaker.recordFailure(now.minusSeconds(10));
                }
            }
            case OPEN -> openAt(breaker, threshold, now.minusSeconds(5));
            case OPEN_EXPIRED -> openAt(breaker, threshold, now.minus(OPEN).minusSeconds(1));
            case HALF_OPEN_TRIAL_HELD -> {
                openAt(breaker, threshold, now.minus(OPEN).minusSeconds(10));
                assertThat(breaker.tryAcquirePermission(now.minusSeconds(5))).isTrue();
            }
            case HALF_OPEN_TRIAL_EXPIRED -> {
                openAt(breaker, threshold, now.minus(OPEN.multipliedBy(3)));
                assertThat(breaker.tryAcquirePermission(now.minus(OPEN).minusSeconds(1))).isTrue();
            }
        }
    }

    private static void openAt(CircuitBreaker breaker, int threshold, Instant at) {
        for (int i = 0; i < threshold; i++) {
            breaker.recordFailure(at);
        }
    }

    /** El estado que verá la petición en {@code now}. */
    private static CircuitState effectiveState(BreakerSetup setup, int threshold) {
        return switch (setup) {
            case CLOSED, CLOSED_WITH_FAILURES -> CircuitState.CLOSED;
            case OPEN -> CircuitState.OPEN;
            case OPEN_EXPIRED, HALF_OPEN_TRIAL_HELD, HALF_OPEN_TRIAL_EXPIRED -> CircuitState.HALF_OPEN;
        };
    }

    private static boolean isOrderedSubsequence(List<GatewayId> sub, List<GatewayId> full) {
        int j = 0;
        for (GatewayId id : sub) {
            while (j < full.size() && !full.get(j).equals(id)) {
                j++;
            }
            if (j == full.size()) {
                return false;
            }
            j++;
        }
        return true;
    }
}
