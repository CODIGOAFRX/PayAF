package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Foto inmutable del estado de un circuit breaker, pensada para el panel.
 * <p>
 * Es una foto del último estado conocido: un circuito OPEN cuyo tiempo ya ha vencido
 * seguirá apareciendo como OPEN hasta que alguien pregunte con {@link CircuitBreaker#allowsRequest}.
 * {@link #retryAt()} permite al panel enseñar la cuenta atrás.
 *
 * @param gateway             pasarela del circuito
 * @param state               estado del circuito
 * @param consecutiveFailures fallos técnicos seguidos desde el último éxito
 * @param openedAt            cuándo se abrió por última vez, si está abierto
 * @param retryAt             a partir de cuándo pasará a HALF_OPEN, si está abierto
 * @param trialsInFlight      peticiones de prueba concedidas en HALF_OPEN y pendientes de resultado
 */
public record CircuitBreakerSnapshot(
        GatewayId gateway,
        CircuitState state,
        int consecutiveFailures,
        Optional<Instant> openedAt,
        Optional<Instant> retryAt,
        int trialsInFlight) {

    public CircuitBreakerSnapshot {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(state);
        Objects.requireNonNull(openedAt);
        Objects.requireNonNull(retryAt);
    }
}
