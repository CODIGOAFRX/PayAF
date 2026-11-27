package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import java.util.Objects;

/**
 * Parámetros de un {@link CircuitBreaker}.
 * <p>
 * Es dominio porque decide cuánta paciencia tiene el negocio con una pasarela que falla:
 * cuántos fallos técnicos seguidos se toleran antes de dejar de mandarle pagos y cuánto
 * se espera antes de volver a probar. Los valores concretos los carga la aplicación desde
 * la configuración, pero su significado y sus límites son del dominio.
 *
 * @param failureThreshold  fallos técnicos consecutivos (ERROR o TIMEOUT) que abren el circuito; al menos 1
 * @param openDuration      cuánto tiempo permanece abierto antes de pasar a HALF_OPEN, y también
 *                          cuánto vive una petición de prueba sin resultado; mayor que cero y de
 *                          24 h como mucho (una pasarela no se aparta un día entero sin que nadie
 *                          lo revise, y el límite evita desbordar al sumarla a un {@code Instant})
 * @param halfOpenMaxTrials peticiones de prueba que se dejan pasar en HALF_OPEN; al menos 1 (normalmente 1)
 */
public record CircuitBreakerConfig(int failureThreshold, Duration openDuration, int halfOpenMaxTrials) {

    /** Límite superior de {@code openDuration}. */
    public static final Duration MAX_OPEN_DURATION = Duration.ofHours(24);

    public CircuitBreakerConfig {
        Objects.requireNonNull(openDuration, "la duración de apertura no puede ser nula");
        if (failureThreshold < 1) {
            throw new InvalidValueException("El umbral de fallos debe ser al menos 1: " + failureThreshold);
        }
        if (openDuration.isNegative() || openDuration.isZero() || openDuration.compareTo(MAX_OPEN_DURATION) > 0) {
            throw new InvalidValueException("La duración de apertura debe ser positiva y de 24 h como mucho: "
                    + openDuration);
        }
        if (halfOpenMaxTrials < 1) {
            throw new InvalidValueException("Debe haber al menos una petición de prueba: " + halfOpenMaxTrials);
        }
    }

    /** Configuración con una única petición de prueba en HALF_OPEN, que es la habitual. */
    public static CircuitBreakerConfig of(int failureThreshold, Duration openDuration) {
        return new CircuitBreakerConfig(failureThreshold, openDuration, 1);
    }
}
