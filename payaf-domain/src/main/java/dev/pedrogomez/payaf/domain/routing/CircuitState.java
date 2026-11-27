package dev.pedrogomez.payaf.domain.routing;

/**
 * Estado del circuit breaker de una pasarela.
 * <ul>
 *   <li>CLOSED: funciona con normalidad y recibe tráfico.</li>
 *   <li>OPEN: ha fallado demasiadas veces seguidas y no recibe tráfico hasta que pase el tiempo de apertura.</li>
 *   <li>HALF_OPEN: ha pasado ese tiempo y se le deja una petición de prueba para ver si se ha recuperado.</li>
 * </ul>
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
