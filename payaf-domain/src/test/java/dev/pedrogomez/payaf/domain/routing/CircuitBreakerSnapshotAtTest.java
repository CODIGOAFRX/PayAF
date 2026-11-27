package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CircuitBreakerSnapshotAtTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration OPEN = Duration.ofSeconds(30);

    private static CircuitBreaker opened() {
        CircuitBreaker breaker = new CircuitBreaker(GatewayId.of("stripe"), CircuitBreakerConfig.of(1, OPEN));
        breaker.recordFailure(T0);
        breaker.pullEvents();
        return breaker;
    }

    @Test
    @DisplayName("OPEN vencido se ve HALF_OPEN sin mover el breaker ni emitir eventos")
    void openDueLooksHalfOpen() {
        CircuitBreaker breaker = opened();

        assertThat(breaker.snapshotAt(T0.plus(OPEN).minusNanos(1)).state()).isEqualTo(CircuitState.OPEN);
        CircuitBreakerSnapshot due = breaker.snapshotAt(T0.plus(OPEN));
        assertThat(due.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(due.trialsInFlight()).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("en HALF_OPEN no cuentan las pruebas caducadas")
    void expiredTrialsDoNotCount() {
        CircuitBreaker breaker = opened();
        Instant halfOpen = T0.plus(OPEN);
        assertThat(breaker.tryAcquirePermission(halfOpen)).isTrue();

        assertThat(breaker.snapshotAt(halfOpen.plus(OPEN).minusNanos(1)).trialsInFlight()).isEqualTo(1);
        assertThat(breaker.snapshotAt(halfOpen.plus(OPEN)).trialsInFlight()).isZero();
        assertThat(breaker.snapshot().trialsInFlight()).isEqualTo(1);
    }
}
