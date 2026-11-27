package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayHealthEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Bordes del {@link CircuitBreaker}, siempre con instantes fijos. */
class CircuitBreakerEdgeTest {

    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Duration OPEN = Duration.ofSeconds(30);
    private static final GatewayId GW = GatewayId.of("redsys");

    private static CircuitBreaker breaker(int threshold, int maxTrials) {
        return new CircuitBreaker(GW, new CircuitBreakerConfig(threshold, OPEN, maxTrials));
    }

    private static CircuitBreaker openedAt(Instant at, int threshold, int maxTrials) {
        CircuitBreaker b = breaker(threshold, maxTrials);
        for (int i = 0; i < threshold; i++) {
            b.recordFailure(at);
        }
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
        b.pullEvents();
        return b;
    }

    // ---------------------------------------------------------------------------------
    // Umbral
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "umbral {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    @DisplayName("se abre justo al llegar a N fallos consecutivos, con ERROR y TIMEOUT mezclados, y no antes")
    void opensExactlyAtThreshold(int threshold) {
        CircuitBreaker b = breaker(threshold, 1);
        for (int i = 1; i < threshold; i++) {
            b.record(i % 2 == 0 ? AttemptOutcome.ERROR : AttemptOutcome.TIMEOUT, T0.plusSeconds(i));
            assertThat(b.state()).as("tras %d fallos", i).isEqualTo(CircuitState.CLOSED);
            assertThat(b.snapshot().consecutiveFailures()).isEqualTo(i);
        }
        assertThat(b.pullEvents()).isEmpty();
        b.record(AttemptOutcome.TIMEOUT, T0.plusSeconds(threshold));
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
        assertThat(b.allowsRequest(T0.plusSeconds(threshold))).isFalse();
        assertThat(b.tryAcquirePermission(T0.plusSeconds(threshold))).isFalse();
        assertThat(b.pullEvents()).singleElement().isEqualTo(new CircuitOpened(GW, T0.plusSeconds(threshold)));
    }

    @ParameterizedTest(name = "{0} reinicia el contador")
    @EnumSource(value = AttemptOutcome.class, names = {"DECLINED", "APPROVED"})
    @DisplayName("DECLINED y APPROVED no cuentan como fallo y reinician el contador en CLOSED")
    void declinedAndApprovedReset(AttemptOutcome success) {
        CircuitBreaker b = breaker(3, 1);
        b.record(AttemptOutcome.ERROR, T0);
        b.record(AttemptOutcome.TIMEOUT, T0);
        b.record(success, T0);
        assertThat(b.snapshot().consecutiveFailures()).isZero();
        b.record(AttemptOutcome.ERROR, T0);
        b.record(AttemptOutcome.ERROR, T0);
        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
        b.record(AttemptOutcome.ERROR, T0);
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("una racha larga de DECLINED nunca abre el circuito")
    void declinedNeverOpens() {
        CircuitBreaker b = breaker(1, 1);
        for (int i = 0; i < 1_000; i++) {
            b.record(AttemptOutcome.DECLINED, T0.plusMillis(i));
        }
        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(b.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("un éxito en CLOSED no emite CircuitClosed (no hay transición)")
    void successInClosedEmitsNothing() {
        CircuitBreaker b = breaker(3, 1);
        b.recordFailure(T0);
        b.recordSuccess(T0);
        b.recordSuccess(T0);
        assertThat(b.pullEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // OPEN → HALF_OPEN: el borde exacto
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("OPEN → HALF_OPEN exactamente en openedAt + openDuration, ni un nanosegundo antes")
    void halfOpensAtExactBoundary() {
        CircuitBreaker b = openedAt(T0, 2, 1);
        Instant retryAt = T0.plus(OPEN);
        assertThat(b.snapshot().retryAt()).contains(retryAt);
        assertThat(b.snapshot().openedAt()).contains(T0);

        assertThat(b.allowsRequest(retryAt.minusNanos(1))).isFalse();
        assertThat(b.tryAcquirePermission(retryAt.minusNanos(1))).isFalse();
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
        assertThat(b.pullEvents()).as("antes del borde no hay transición").isEmpty();

        assertThat(b.allowsRequest(retryAt)).isTrue();
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(b.pullEvents()).singleElement().isEqualTo(new CircuitHalfOpened(GW, retryAt));
        assertThat(b.snapshot().retryAt()).isEmpty();
    }

    @Test
    @DisplayName("el paso a HALF_OPEN es perezoso: sin preguntar, la foto sigue en OPEN aunque haya vencido")
    void halfOpenIsLazy() {
        CircuitBreaker b = openedAt(T0, 1, 1);
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
        assertThat(b.snapshot().state()).isEqualTo(CircuitState.OPEN);
        assertThat(b.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("un reloj que va hacia atrás no abre la puerta antes de tiempo")
    void clockGoingBackwards() {
        CircuitBreaker b = openedAt(T0, 1, 1);
        assertThat(b.allowsRequest(T0.minusSeconds(3_600))).isFalse();
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
    }

    // ---------------------------------------------------------------------------------
    // HALF_OPEN: consultar frente a reservar
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("allowsRequest no gasta la prueba aunque se llame mil veces; tryAcquirePermission sí")
    void allowsRequestDoesNotConsumeTrial() {
        CircuitBreaker b = openedAt(T0, 1, 1);
        Instant t = T0.plus(OPEN);
        for (int i = 0; i < 1_000; i++) {
            assertThat(b.allowsRequest(t.plusMillis(i))).isTrue();
        }
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.pullEvents()).as("un solo HalfOpened pese a las mil consultas")
                .singleElement().isInstanceOf(CircuitHalfOpened.class);

        assertThat(b.tryAcquirePermission(t)).isTrue();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(1);
        assertThat(b.tryAcquirePermission(t)).as("segunda adquisición con maxTrials=1").isFalse();
        assertThat(b.allowsRequest(t)).as("con la prueba en vuelo ya no admite más").isFalse();
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(b.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("tryAcquirePermission en OPEN vencido hace la transición y gasta la prueba en una sola llamada")
    void tryAcquireTransitionsAndConsumes() {
        CircuitBreaker b = openedAt(T0, 1, 1);
        assertThat(b.tryAcquirePermission(T0.plus(OPEN))).isTrue();
        assertThat(b.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(b.tryAcquirePermission(T0.plus(OPEN))).isFalse();
        assertThat(b.pullEvents()).singleElement().isInstanceOf(CircuitHalfOpened.class);
    }

    @Test
    @DisplayName("con maxTrials=3 se conceden exactamente tres pruebas")
    void multipleTrials() {
        CircuitBreaker b = openedAt(T0, 1, 3);
        Instant t = T0.plus(OPEN);
        assertThat(b.tryAcquirePermission(t)).isTrue();
        assertThat(b.tryAcquirePermission(t)).isTrue();
        assertThat(b.tryAcquirePermission(t)).isTrue();
        assertThat(b.tryAcquirePermission(t)).isFalse();
        assertThat(b.snapshot().trialsInFlight()).isEqualTo(3);
    }

    @Test
    @DisplayName("tryAcquirePermission en CLOSED siempre concede y no reserva nada")
    void tryAcquireInClosed() {
        CircuitBreaker b = breaker(3, 1);
        for (int i = 0; i < 10; i++) {
            assertThat(b.tryAcquirePermission(T0)).isTrue();
        }
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.pullEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // HALF_OPEN → CLOSED | OPEN
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("un éxito en HALF_OPEN lo cierra del todo: contador a cero y hacen falta N fallos nuevos")
    void successInHalfOpenCloses() {
        CircuitBreaker b = openedAt(T0, 3, 1);
        Instant t = T0.plus(OPEN);
        assertThat(b.tryAcquirePermission(t)).isTrue();
        b.record(AttemptOutcome.DECLINED, t.plusSeconds(1));

        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
        CircuitBreakerSnapshot snap = b.snapshot();
        assertThat(snap.consecutiveFailures()).isZero();
        assertThat(snap.trialsInFlight()).isZero();
        assertThat(snap.openedAt()).isEmpty();
        assertThat(snap.retryAt()).isEmpty();
        assertThat(b.pullEvents()).containsExactly(new CircuitHalfOpened(GW, t), new CircuitClosed(GW, t.plusSeconds(1)));

        b.recordFailure(t.plusSeconds(2));
        b.recordFailure(t.plusSeconds(3));
        assertThat(b.state()).as("dos fallos con umbral 3").isEqualTo(CircuitState.CLOSED);
        b.recordFailure(t.plusSeconds(4));
        assertThat(b.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("un fallo en HALF_OPEN lo reabre al instante con un retryAt nuevo contado desde ese fallo")
    void failureInHalfOpenReopensWithNewRetryAt() {
        CircuitBreaker b = openedAt(T0, 3, 1);
        Instant halfOpen = T0.plus(OPEN).plusSeconds(7);
        assertThat(b.tryAcquirePermission(halfOpen)).isTrue();
        Instant failure = halfOpen.plusSeconds(2);
        b.record(AttemptOutcome.TIMEOUT, failure);

        assertThat(b.state()).as("un solo fallo basta en HALF_OPEN aunque el umbral sea 3").isEqualTo(CircuitState.OPEN);
        assertThat(b.snapshot().openedAt()).contains(failure);
        assertThat(b.snapshot().retryAt()).contains(failure.plus(OPEN));
        assertThat(b.snapshot().trialsInFlight()).isZero();
        assertThat(b.allowsRequest(failure.plus(OPEN).minusNanos(1))).isFalse();
        assertThat(b.pullEvents()).containsExactly(new CircuitHalfOpened(GW, halfOpen), new CircuitOpened(GW, failure));
        assertThat(b.allowsRequest(failure.plus(OPEN))).isTrue();
        assertThat(b.tryAcquirePermission(failure.plus(OPEN))).as("la prueba se repone en el nuevo HALF_OPEN").isTrue();
    }

    // ---------------------------------------------------------------------------------
    // Resultados tardíos en OPEN
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "resultado tardío {0} en OPEN")
    @EnumSource(AttemptOutcome.class)
    @DisplayName("los resultados que llegan en OPEN se ignoran: ni cierran, ni alargan, ni emiten eventos")
    void lateResultsInOpenAreIgnored(AttemptOutcome outcome) {
        CircuitBreaker b = openedAt(T0, 2, 1);
        CircuitBreakerSnapshot before = b.snapshot();
        b.record(outcome, T0.plusSeconds(20));
        b.record(outcome, T0.plusSeconds(25));
        assertThat(b.snapshot()).isEqualTo(before);
        assertThat(b.pullEvents()).isEmpty();
        assertThat(b.allowsRequest(T0.plus(OPEN))).as("el retryAt original no se ha movido").isTrue();
    }

    @Test
    @DisplayName("un resultado tardío llegado en OPEN, aunque ya haya vencido openDuration, no cuenta como prueba")
    void lateResultAfterExpiryWithoutAskingIsIgnored() {
        CircuitBreaker b = openedAt(T0, 1, 1);
        b.recordSuccess(T0.plus(OPEN).plusSeconds(5));
        assertThat(b.state()).as("sigue OPEN: nadie ha preguntado con allowsRequest").isEqualTo(CircuitState.OPEN);
        assertThat(b.pullEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Eventos: uno por transición
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("varios ciclos completos: exactamente un evento por transición, en orden y con su instante")
    void oneEventPerTransitionAcrossCycles() {
        CircuitBreaker b = breaker(2, 1);
        Instant t = T0;
        for (int i = 0; i < 5; i++) {
            b.recordFailure(t);
            b.recordFailure(t);
            b.recordFailure(t); // tardío, ya en OPEN
            Instant retry = t.plus(OPEN);
            b.allowsRequest(retry);
            b.allowsRequest(retry);
            b.tryAcquirePermission(retry);
            b.tryAcquirePermission(retry);
            b.recordSuccess(retry.plusSeconds(1));
            b.recordSuccess(retry.plusSeconds(1)); // ya en CLOSED
            t = retry.plusSeconds(10);
        }
        List<DomainEvent> events = b.pullEvents();
        assertThat(events).hasSize(15);
        for (int i = 0; i < 15; i += 3) {
            assertThat(events.get(i)).isInstanceOf(CircuitOpened.class);
            assertThat(events.get(i + 1)).isInstanceOf(CircuitHalfOpened.class);
            assertThat(events.get(i + 2)).isInstanceOf(CircuitClosed.class);
        }
        assertThat(events).allSatisfy(e -> assertThat(((GatewayHealthEvent) e).gateway()).isEqualTo(GW));
        assertThat(events).extracting(DomainEvent::occurredAt).isSorted();
        assertThat(b.pullEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Configuración y nulos
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("la configuración rechaza umbral < 1, duración ≤ 0 y maxTrials < 1")
    void configValidation() {
        assertThatThrownBy(() -> new CircuitBreakerConfig(0, OPEN, 1)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(-1, OPEN, 1)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, Duration.ZERO, 1)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, Duration.ofNanos(-1), 1))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, OPEN, 0)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, null, 1)).isInstanceOf(NullPointerException.class);
        assertThat(CircuitBreakerConfig.of(4, OPEN).halfOpenMaxTrials()).isEqualTo(1);
        assertThat(new CircuitBreakerConfig(1, Duration.ofNanos(1), 1).openDuration()).isPositive();
    }

    @Test
    @DisplayName("nulos en el constructor y en las operaciones se rechazan")
    void nulls() {
        CircuitBreaker b = breaker(1, 1);
        assertThatThrownBy(() -> new CircuitBreaker(null, CircuitBreakerConfig.of(1, OPEN)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CircuitBreaker(GW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.allowsRequest(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.tryAcquirePermission(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.recordSuccess(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.recordFailure(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.record(null, T0)).isInstanceOf(NullPointerException.class);
        assertThat(b.state()).isEqualTo(CircuitState.CLOSED);
    }
}
