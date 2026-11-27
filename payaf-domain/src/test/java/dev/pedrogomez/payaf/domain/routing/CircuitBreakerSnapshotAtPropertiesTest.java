package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link CircuitBreaker#snapshotAt} es la vista del panel: tiene que coincidir siempre con lo
 * que vería la siguiente petición. Para cualquier historia se construyen réplicas idénticas
 * del breaker y se compara {@code snapshotAt(now)} del original con lo que deja
 * {@code allowsRequest(now)} en una réplica, y con lo que concede {@code tryAcquirePermission(now)}
 * en otra. El original no debe cambiar.
 */
class CircuitBreakerSnapshotAtPropertiesTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration OPEN = Duration.ofSeconds(10);

    enum Kind { SUCCESS, FAILURE, DECLINED, ALLOWS, ACQUIRE }

    /** Una operación sobre el breaker, {@code advanceSeconds} después de la anterior. */
    record Op(Kind kind, int advanceSeconds) {
    }

    record History(int threshold, int maxTrials, List<Op> ops, int queryAfterSeconds, int queryExtraNanos) {
    }

    @Provide
    Arbitrary<History> histories() {
        Arbitrary<Op> op = Combinators.combine(
                Arbitraries.of(Kind.class),
                // Saltos de segundos enteros hasta 2,5 aperturas: caen a menudo justo en los límites.
                Arbitraries.integers().between(0, 25))
                .as(Op::new);
        return Combinators.combine(
                Arbitraries.integers().between(1, 3),
                Arbitraries.integers().between(1, 3),
                op.list().ofMaxSize(30),
                Arbitraries.integers().between(0, 35),
                Arbitraries.of(0, 0, 0, 1, -1))
                .as(History::new);
    }

    private static Replay replay(History history) {
        CircuitBreaker breaker = new CircuitBreaker(GatewayId.of("gw-x"),
                new CircuitBreakerConfig(history.threshold(), OPEN, history.maxTrials()));
        Instant t = T0;
        for (Op op : history.ops()) {
            t = t.plusSeconds(op.advanceSeconds());
            switch (op.kind()) {
                case SUCCESS -> breaker.record(AttemptOutcome.APPROVED, t);
                case DECLINED -> breaker.record(AttemptOutcome.DECLINED, t);
                case FAILURE -> breaker.record(AttemptOutcome.ERROR, t);
                case ALLOWS -> breaker.allowsRequest(t);
                case ACQUIRE -> breaker.tryAcquirePermission(t);
            }
        }
        return new Replay(breaker, t);
    }

    private record Replay(CircuitBreaker breaker, Instant last) {
    }

    @Property(tries = 2_000)
    void snapshotAtMatchesWhatTheNextRequestWouldSee(@ForAll("histories") History history) {
        Replay original = replay(history);
        Instant now = original.last().plusSeconds(history.queryAfterSeconds()).plusNanos(history.queryExtraNanos());
        if (now.isBefore(original.last())) {
            now = original.last();
        }
        CircuitBreakerSnapshot before = original.breaker().snapshot();
        CircuitState stateBefore = original.breaker().state();

        CircuitBreakerSnapshot shown = original.breaker().snapshotAt(now);

        // Réplica 1: lo que deja allowsRequest(now).
        CircuitBreaker asked = replay(history).breaker();
        boolean allowed = asked.allowsRequest(now);
        CircuitBreakerSnapshot seen = asked.snapshot();
        assertThat(shown.state()).as("estado").isEqualTo(seen.state());
        assertThat(shown.trialsInFlight()).as("pruebas en vuelo").isEqualTo(seen.trialsInFlight());
        assertThat(shown).as("snapshot completo").isEqualTo(seen);

        // Réplica 2: tryAcquirePermission(now) concede justo cuando la vista lo predice.
        CircuitBreaker acquiring = replay(history).breaker();
        boolean predicted = switch (shown.state()) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> shown.trialsInFlight() < history.maxTrials();
        };
        assertThat(acquiring.tryAcquirePermission(now)).isEqualTo(predicted).isEqualTo(allowed);

        // El original no se ha movido: ni estado, ni snapshot, ni eventos.
        assertThat(original.breaker().state()).isEqualTo(stateBefore);
        assertThat(original.breaker().snapshot()).isEqualTo(before);
        assertThat(original.breaker().pullEvents()).isEqualTo(replay(history).breaker().pullEvents());
    }
}
