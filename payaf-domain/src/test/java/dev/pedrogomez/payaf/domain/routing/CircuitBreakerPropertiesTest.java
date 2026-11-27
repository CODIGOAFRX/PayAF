package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Propiedades del {@link CircuitBreaker} ante secuencias aleatorias de consultas,
 * reservas y resultados con un reloj que solo avanza.
 */
class CircuitBreakerPropertiesTest {

    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final GatewayId GW = GatewayId.of("gw-prop");

    /** Únicas transiciones de la máquina de estados del circuito y el evento que las anuncia. */
    private static final Map<CircuitState, Map<CircuitState, Class<? extends DomainEvent>>> TRANSITIONS = Map.of(
            CircuitState.CLOSED, Map.of(CircuitState.OPEN, CircuitOpened.class),
            CircuitState.OPEN, Map.of(CircuitState.HALF_OPEN, CircuitHalfOpened.class),
            CircuitState.HALF_OPEN, Map.of(CircuitState.CLOSED, CircuitClosed.class,
                    CircuitState.OPEN, CircuitOpened.class));

    enum Kind { ALLOWS, ACQUIRE, RESULT }

    record Step(Kind kind, AttemptOutcome outcome, long advanceMillis) {
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Step> step = Combinators.combine(
                Arbitraries.of(Kind.class),
                Arbitraries.of(AttemptOutcome.class),
                Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(5, Arbitraries.just(0L)),
                        net.jqwik.api.Tuple.of(5, Arbitraries.longs().between(1, 2_000)),
                        net.jqwik.api.Tuple.of(2, Arbitraries.longs().between(9_000, 11_000)),
                        net.jqwik.api.Tuple.of(2, Arbitraries.of(10_000L, 9_999L, 5_000L))))
                .as(Step::new);
        return step.list().ofMaxSize(80);
    }

    @Property(tries = 2_000)
    @Label("cada cambio de estado emite un evento del tipo correcto, y las pruebas caducan a openDuration de concederse")
    void eventsMatchTransitions(@ForAll @IntRange(min = 1, max = 4) int threshold,
                                @ForAll @IntRange(min = 1, max = 3) int maxTrials,
                                @ForAll("steps") List<Step> steps) {
        Duration openDuration = Duration.ofSeconds(10);
        CircuitBreaker b = new CircuitBreaker(GW, new CircuitBreakerConfig(threshold, openDuration, maxTrials));
        Instant now = T0;
        // Modelo: instante de concesión de cada prueba de HALF_OPEN aún sin resultado.
        List<Instant> trials = new ArrayList<>();

        for (Step s : steps) {
            now = now.plusMillis(s.advanceMillis());
            Instant at = now;
            CircuitState before = b.state();
            CircuitBreakerSnapshot snapBefore = b.snapshot();
            Boolean answer = null;

            switch (s.kind()) {
                case ALLOWS -> answer = b.allowsRequest(now);
                case ACQUIRE -> answer = b.tryAcquirePermission(now);
                case RESULT -> b.record(s.outcome(), now);
            }
            CircuitState after = b.state();
            List<DomainEvent> events = b.pullEvents();

            if (before != after) {
                trials.clear();
            }
            if (after == CircuitState.HALF_OPEN && s.kind() != Kind.RESULT) {
                // Caducan las concedidas hace openDuration o más (now >= concedida + openDuration).
                trials.removeIf(granted -> !at.isBefore(granted.plus(openDuration)));
                boolean free = trials.size() < maxTrials;
                if (s.kind() == Kind.ALLOWS) {
                    assertThat(answer).as("allowsRequest en HALF_OPEN con %s pruebas vivas", trials.size())
                            .isEqualTo(free);
                } else {
                    assertThat(answer).as("tryAcquirePermission en HALF_OPEN con %s pruebas vivas", trials.size())
                            .isEqualTo(free);
                    if (answer) {
                        trials.add(at);
                    }
                }
            }
            if (after == CircuitState.OPEN && answer != null) {
                assertThat(answer).isFalse();
            }
            if (after == CircuitState.CLOSED && answer != null) {
                assertThat(answer).isTrue();
            }

            if (before == after) {
                assertThat(events).as("%s sin cambio de estado (%s)", s, before).isEmpty();
            } else {
                Class<? extends DomainEvent> expected = TRANSITIONS.get(before).get(after);
                assertThat(expected).as("transición ilegal %s → %s por %s", before, after, s).isNotNull();
                assertThat(events).singleElement().isInstanceOf(expected);
                assertThat(events.getFirst().occurredAt()).isEqualTo(now);
            }

            // OPEN → HALF_OPEN solo cuando ha vencido el tiempo, y nunca por un resultado.
            if (before == CircuitState.OPEN && after == CircuitState.HALF_OPEN) {
                assertThat(s.kind()).isNotEqualTo(Kind.RESULT);
                assertThat(now).isAfterOrEqualTo(snapBefore.retryAt().orElseThrow());
            }
            // En OPEN los resultados se ignoran por completo.
            if (before == CircuitState.OPEN && s.kind() == Kind.RESULT) {
                assertThat(b.snapshot()).isEqualTo(snapBefore);
            }
            // En HALF_OPEN cualquier resultado decide, aunque venga de una prueba caducada.
            if (before == CircuitState.HALF_OPEN && s.kind() == Kind.RESULT) {
                assertThat(after).isEqualTo(s.outcome().isTechnicalFailure() ? CircuitState.OPEN : CircuitState.CLOSED);
            }
            // Las pruebas vivas nunca superan maxTrials y la foto cuenta las mismas que el modelo.
            CircuitBreakerSnapshot snap = b.snapshot();
            assertThat(trials.size()).isLessThanOrEqualTo(maxTrials);
            assertThat(snap.trialsInFlight()).isEqualTo(after == CircuitState.HALF_OPEN ? trials.size() : 0);
            // La foto es coherente con el estado.
            assertThat(snap.openedAt().isPresent()).isEqualTo(after == CircuitState.OPEN);
            assertThat(snap.retryAt().isPresent()).isEqualTo(after == CircuitState.OPEN);
            snap.openedAt().ifPresent(o -> assertThat(snap.retryAt()).contains(o.plus(openDuration)));
            if (after == CircuitState.CLOSED) {
                assertThat(snap.consecutiveFailures()).isBetween(0, threshold - 1);
            }
        }
    }

    @Property(tries = 500)
    @Label("en CLOSED, el circuito se abre si y solo si hay N fallos técnicos seguidos")
    void opensIffThresholdConsecutiveFailures(@ForAll @IntRange(min = 1, max = 5) int threshold,
                                              @ForAll("outcomes") List<AttemptOutcome> outcomes) {
        CircuitBreaker b = new CircuitBreaker(GW, CircuitBreakerConfig.of(threshold, Duration.ofMinutes(1)));
        int streak = 0;
        for (AttemptOutcome o : outcomes) {
            b.record(o, T0);
            if (b.state() == CircuitState.OPEN) {
                break;
            }
            streak = o.isTechnicalFailure() ? streak + 1 : 0;
            assertThat(streak).as("racha de fallos sin abrir").isLessThan(threshold);
        }
        int finalStreak = 0;
        int maxStreak = 0;
        for (AttemptOutcome o : outcomes) {
            finalStreak = o.isTechnicalFailure() ? finalStreak + 1 : 0;
            maxStreak = Math.max(maxStreak, finalStreak);
        }
        assertThat(b.state() == CircuitState.OPEN).isEqualTo(maxStreak >= threshold);
    }

    @Provide
    Arbitrary<List<AttemptOutcome>> outcomes() {
        return Arbitraries.of(AttemptOutcome.class).list().ofMaxSize(40);
    }
}
