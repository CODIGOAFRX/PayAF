package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Un breaker que pasa de OPEN a HALF_OPEN tiene que poder guardarse: el store de PostgreSQL
 * llama a {@code memento()} justo después de esa transición (AuthorizePaymentService.decide).
 * Si {@code memento()} lanza, el enrutado cae en la red de seguridad y el pago acaba FAILED con
 * ROUTING_ERROR aunque haya otras pasarelas sanas.
 */
class CircuitBreakerHalfOpenMementoBugTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration OPEN = Duration.ofSeconds(15);

    private static CircuitBreaker openedBreaker() {
        CircuitBreaker breaker = new CircuitBreaker(GatewayId.of("redsys"), CircuitBreakerConfig.of(3, OPEN));
        breaker.recordFailure(T0);
        breaker.recordFailure(T0);
        breaker.recordFailure(T0);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        return breaker;
    }

    @Test
    void elMementoDeUnBreakerRecienPasadoAHalfOpenEsValido() {
        CircuitBreaker breaker = openedBreaker();

        assertThat(breaker.allowsRequest(T0.plus(OPEN))).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        assertThatCode(breaker::memento).doesNotThrowAnyException();
    }

    @Test
    void elMementoTrasConcederLaPruebaDeHalfOpenEsValido() {
        CircuitBreaker breaker = openedBreaker();

        assertThat(breaker.tryAcquirePermission(T0.plus(OPEN))).isTrue();

        assertThatCode(breaker::memento).doesNotThrowAnyException();
    }
}
