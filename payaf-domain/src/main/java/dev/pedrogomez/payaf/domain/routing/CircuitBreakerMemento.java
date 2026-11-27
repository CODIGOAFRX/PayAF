package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Estado interno completo de un {@link CircuitBreaker}, para guardarlo y reconstruirlo
 * ({@link CircuitBreaker#memento()} y {@link CircuitBreaker#restore}).
 * <p>
 * No es lo mismo que {@link CircuitBreakerSnapshot}: la foto del panel solo cuenta las pruebas
 * en vuelo, y para reconstruir el breaker hace falta saber cuándo se concedió cada una (si no,
 * no se podrían caducar). La configuración no va aquí: es de quien crea el breaker, y al
 * reconstruirlo manda la vigente, no la que hubiera cuando se guardó.
 *
 * @param state               estado del circuito
 * @param consecutiveFailures fallos técnicos seguidos; cero o más
 * @param openedAt            cuándo se abrió; obligatorio en OPEN y vacío en los demás estados
 * @param trialsAcquiredAt    cuándo se concedió cada prueba de HALF_OPEN sin resultado; vacía fuera de HALF_OPEN
 */
public record CircuitBreakerMemento(CircuitState state, int consecutiveFailures, Optional<Instant> openedAt,
                                    List<Instant> trialsAcquiredAt) {

    public CircuitBreakerMemento {
        Objects.requireNonNull(state, "el estado no puede ser nulo");
        Objects.requireNonNull(openedAt, "el instante de apertura opcional no puede ser nulo: usa Optional.empty()");
        trialsAcquiredAt = List.copyOf(Objects.requireNonNull(trialsAcquiredAt, "las pruebas no pueden ser nulas"));
        if (consecutiveFailures < 0) {
            throw new InvalidValueException("Los fallos seguidos no pueden ser negativos: " + consecutiveFailures);
        }
        if ((state == CircuitState.OPEN) != openedAt.isPresent()) {
            throw new InvalidValueException("El instante de apertura solo existe, y es obligatorio, en OPEN");
        }
        if (state != CircuitState.HALF_OPEN && !trialsAcquiredAt.isEmpty()) {
            throw new InvalidValueException("Solo puede haber pruebas en vuelo en HALF_OPEN");
        }
    }
}
