package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Caducidad de las pruebas de HALF_OPEN: una prueba concedida sin resultado caduca cuando
 * {@code now >= concedida + openDuration}. La liberan, de forma perezosa, allowsRequest y
 * tryAcquirePermission. Un resultado tardío de una prueba caducada se aplica igual.
 */
class CircuitBreakerTrialExpiryTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Duration OPEN = Duration.ofSeconds(30);
    private static final GatewayId GW = GatewayId.of("adyen");

    /** Breaker que entra en HALF_OPEN en {@code T0 + OPEN}, con los eventos ya recogidos. */
    private static CircuitBreaker halfOpen(int maxTrials) {
        CircuitBreaker b = new CircuitBreaker(GW, new CircuitBreakerConfig(1, OPEN, maxTrials));
        b.recordFailure(T0);
        assertThat(b.allowsRequest(T0.plus(OPEN))).isTrue();
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
        b.pullEvents();
        return b;
    }

    @Test
    @DisplayName("la prueba caduca justo en concedida + openDuration: 1 ns antes sigue reservada")
    void trialExpiresAtExactBoundary() {
        CircuitBreaker b = halfOpen(1);
        Instant granted = T0.plus(OPEN).plusSeconds(3);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        Instant expiry = granted.plus(OPEN);

        assertThat(b.allowsRequest(expiry.minusNanos(1))).isFalse();
        assertThat(b.tryAcquirePermission(expiry.minusNanos(1))).isFalse();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);

        assertThat(b.allowsRequest(expiry)).isTrue();
        assertThat(b.snapshot().trialsInFlight()).as("allowsRequest la libera pero no reserva otra").isZero();
        assertThat(b.allowsRequest(expiry)).isTrue();
        assertThat(b.tryAcquirePermission(expiry)).isTrue();
        assertThat(b.tryAcquirePermission(expiry)).isFalse();
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(b.pullEvents()).as("caducar una prueba no es una transición").isEmpty();
    }

    @Test
    @DisplayName("tryAcquirePermission también libera la caducada y reserva la nueva en la misma llamada")
    void tryAcquireReleasesExpiredTrial() {
        CircuitBreaker b = halfOpen(1);
        Instant granted = T0.plus(OPEN);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        assertThat(b.tryAcquirePermission(granted.plus(OPEN).minusNanos(1))).isFalse();
        assertThat(b.tryAcquirePermission(granted.plus(OPEN))).isTrue();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);
        assertThat(b.tryAcquirePermission(granted.plus(OPEN))).isFalse();
    }

    @Test
    @DisplayName("el plazo cuenta desde la concesión, no desde el paso a HALF_OPEN")
    void expiryCountsFromGrantNotFromHalfOpen() {
        CircuitBreaker b = halfOpen(1);
        Instant halfOpenAt = T0.plus(OPEN);
        Instant granted = halfOpenAt.plusSeconds(20);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        assertThat(b.allowsRequest(halfOpenAt.plus(OPEN))).as("solo han pasado 10 s desde la concesión").isFalse();
        assertThat(b.allowsRequest(granted.plus(OPEN))).isTrue();
    }

    @Test
    @DisplayName("con varias pruebas, cada una caduca a su hora")
    void eachTrialExpiresOnItsOwn() {
        CircuitBreaker b = halfOpen(2);
        Instant first = T0.plus(OPEN);
        Instant second = first.plusSeconds(5);
        assertThat(b.tryAcquirePermission(first)).isTrue();
        assertThat(b.tryAcquirePermission(second)).isTrue();
        assertThat(b.allowsRequest(second)).isFalse();

        assertThat(b.allowsRequest(first.plus(OPEN))).isTrue();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);
        assertThat(b.tryAcquirePermission(first.plus(OPEN))).isTrue();
        assertThat(b.tryAcquirePermission(first.plus(OPEN))).isFalse();

        assertThat(b.allowsRequest(second.plus(OPEN))).isTrue();
        assertThat(b.snapshot().trialsInFlight()).as("solo ha caducado la segunda").isEqualTo(1);
    }

    @Test
    @DisplayName("la liberación es perezosa: sin preguntar, la foto sigue contando la prueba caducada")
    void releaseIsLazy() {
        CircuitBreaker b = halfOpen(1);
        assertThat(b.tryAcquirePermission(T0.plus(OPEN))).isTrue();
        // Pasa mucho tiempo y nadie pregunta.
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("un resultado tardío de éxito de una prueba caducada cierra el circuito")
    void lateSuccessOfExpiredTrialCloses() {
        CircuitBreaker b = halfOpen(1);
        Instant granted = T0.plus(OPEN);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        assertThat(b.allowsRequest(granted.plus(OPEN).plusSeconds(1))).isTrue(); // la caduca

        b.record(AttemptOutcome.APPROVED, granted.plus(OPEN).plusSeconds(2));
        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.pullEvents()).containsExactly(new CircuitClosed(GW, granted.plus(OPEN).plusSeconds(2)));
    }

    @ParameterizedTest(name = "fallo tardío {0}")
    @EnumSource(value = AttemptOutcome.class, names = {"ERROR", "TIMEOUT"})
    @DisplayName("un fallo tardío de una prueba caducada reabre el circuito con un retryAt nuevo")
    void lateFailureOfExpiredTrialReopens(AttemptOutcome failure) {
        CircuitBreaker b = halfOpen(1);
        Instant granted = T0.plus(OPEN);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        assertThat(b.tryAcquirePermission(granted.plus(OPEN))).isTrue(); // caduca la vieja y concede otra
        Instant late = granted.plus(OPEN).plusSeconds(4);

        b.record(failure, late);
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
        assertThat(b.snapshot().retryAt()).contains(late.plus(OPEN));
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.pullEvents()).containsExactly(new CircuitOpened(GW, late));
    }

    @Test
    @DisplayName("un resultado tardío sin haber liberado la prueba también se aplica")
    void lateResultWithoutReleaseApplies() {
        CircuitBreaker b = halfOpen(1);
        assertThat(b.tryAcquirePermission(T0.plus(OPEN))).isTrue();
        b.recordSuccess(T0.plus(OPEN).plus(OPEN).plusSeconds(100));
        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("tras reabrirse y volver a HALF_OPEN, las pruebas del ciclo anterior no cuentan")
    void trialsDoNotLeakAcrossCycles() {
        CircuitBreaker b = halfOpen(1);
        assertThat(b.tryAcquirePermission(T0.plus(OPEN))).isTrue();
        Instant failure = T0.plus(OPEN).plusSeconds(1);
        b.recordFailure(failure);
        assertThat(b.allowsRequest(failure.plus(OPEN))).isTrue();
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.tryAcquirePermission(failure.plus(OPEN))).isTrue();
    }

    @Test
    @DisplayName("un reloj que retrocede no hace caducar la prueba")
    void clockBackwardsDoesNotExpire() {
        CircuitBreaker b = halfOpen(1);
        Instant granted = T0.plus(OPEN).plusSeconds(10);
        assertThat(b.tryAcquirePermission(granted)).isTrue();
        assertThat(b.allowsRequest(granted.minusSeconds(3_600))).isFalse();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // Límite de openDuration
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("openDuration admite hasta 24 h exactas y rechaza cualquier cosa por encima")
    void openDurationUpperBound() {
        assertThat(new CircuitBreakerConfig(1, Duration.ofHours(24), 1).openDuration()).isEqualTo(Duration.ofHours(24));
        assertThat(CircuitBreakerConfig.MAX_OPEN_DURATION).isEqualTo(Duration.ofHours(24));
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, Duration.ofHours(24).plusNanos(1), 1))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> CircuitBreakerConfig.of(1, Duration.ofDays(365)))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> CircuitBreakerConfig.of(1, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("con la duración máxima, abrir, caducar la prueba y la foto no desbordan")
    void maxDurationDoesNotOverflow() {
        Duration max = CircuitBreakerConfig.MAX_OPEN_DURATION;
        CircuitBreaker b = new CircuitBreaker(GW, CircuitBreakerConfig.of(1, max));
        b.recordFailure(T0);
        assertThat(b.snapshot().retryAt()).contains(T0.plus(max));
        assertThat(b.allowsRequest(T0.plus(max).minusNanos(1))).isFalse();
        assertThat(b.tryAcquirePermission(T0.plus(max))).isTrue();
        assertThat(b.allowsRequest(T0.plus(max).plus(max))).isTrue();
    }
}
