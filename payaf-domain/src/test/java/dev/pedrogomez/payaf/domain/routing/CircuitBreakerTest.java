package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final GatewayId GW = GatewayId.of("adyen");

    private static CircuitBreaker breaker() {
        return new CircuitBreaker(GW, CircuitBreakerConfig.of(3, Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("se abre tras N fallos técnicos seguidos y deja de admitir tráfico")
    void opensAfterThreshold() {
        CircuitBreaker breaker = breaker();
        breaker.recordFailure(T0);
        breaker.recordFailure(T0);
        assertThat(breaker.allowsRequest(T0)).isTrue();

        breaker.recordFailure(T0.plusSeconds(1));
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allowsRequest(T0.plusSeconds(10))).isFalse();
        assertThat(breaker.snapshot().retryAt()).contains(T0.plusSeconds(31));
        assertThat(breaker.pullEvents()).singleElement().isInstanceOf(CircuitOpened.class);
    }

    @Test
    @DisplayName("un éxito reinicia el contador y DECLINED no cuenta como fallo")
    void successAndDeclinedResetCounter() {
        CircuitBreaker breaker = breaker();
        breaker.recordFailure(T0);
        breaker.recordFailure(T0);
        breaker.record(AttemptOutcome.DECLINED, T0);
        breaker.recordFailure(T0);
        breaker.recordFailure(T0);

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().consecutiveFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("al vencer openDuration pasa a HALF_OPEN y un éxito lo cierra")
    void recoversThroughHalfOpen() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(T0);
        }
        breaker.pullEvents();

        assertThat(breaker.allowsRequest(T0.plusSeconds(29))).isFalse();
        assertThat(breaker.allowsRequest(T0.plusSeconds(30))).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        // allowsRequest no gasta la prueba; tryAcquirePermission sí.
        assertThat(breaker.allowsRequest(T0.plusSeconds(31))).isTrue();
        assertThat(breaker.tryAcquirePermission(T0.plusSeconds(31))).isTrue();
        assertThat(breaker.tryAcquirePermission(T0.plusSeconds(31))).isFalse();

        breaker.recordSuccess(T0.plusSeconds(32));
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.pullEvents()).hasExactlyElementsOfTypes(CircuitHalfOpened.class, CircuitClosed.class);
    }

    @Test
    @DisplayName("un fallo en HALF_OPEN lo vuelve a abrir y el tiempo cuenta desde ese fallo")
    void failureInHalfOpenReopens() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(T0);
        }
        assertThat(breaker.tryAcquirePermission(T0.plusSeconds(30))).isTrue();
        breaker.recordFailure(T0.plusSeconds(31));

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allowsRequest(T0.plusSeconds(60))).isFalse();
        assertThat(breaker.allowsRequest(T0.plusSeconds(61))).isTrue();
        assertThat(breaker.pullEvents()).hasExactlyElementsOfTypes(
                CircuitOpened.class, CircuitHalfOpened.class, CircuitOpened.class, CircuitHalfOpened.class);
    }

    @Test
    @DisplayName("una prueba de HALF_OPEN sin resultado caduca en openDuration y se vuelve a conceder")
    void stuckTrialExpires() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(T0);
        }
        Instant acquired = T0.plusSeconds(30);
        assertThat(breaker.tryAcquirePermission(acquired)).isTrue();

        assertThat(breaker.allowsRequest(acquired.plusSeconds(29))).isFalse();
        assertThat(breaker.tryAcquirePermission(acquired.plusSeconds(29))).isFalse();
        assertThat(breaker.snapshot().trialsInFlight()).isEqualTo(1);

        assertThat(breaker.allowsRequest(acquired.plusSeconds(30))).isTrue();
        assertThat(breaker.snapshot().trialsInFlight()).isZero();
        assertThat(breaker.tryAcquirePermission(acquired.plusSeconds(30))).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.recordSuccess(acquired.plusSeconds(31));
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("openDuration tiene que ser mayor que cero y de 24 h como mucho")
    void openDurationBounds() {
        assertThat(CircuitBreakerConfig.of(1, Duration.ofHours(24)).openDuration()).isEqualTo(Duration.ofHours(24));
        assertThatThrownBy(() -> CircuitBreakerConfig.of(1, Duration.ofHours(24).plusNanos(1)))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> CircuitBreakerConfig.of(1, Duration.ZERO))
                .isInstanceOf(InvalidValueException.class);
    }
}
