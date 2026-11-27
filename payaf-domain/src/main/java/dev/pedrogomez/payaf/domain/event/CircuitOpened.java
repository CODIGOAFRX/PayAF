package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Objects;

/** El circuit breaker de la pasarela se ha abierto: deja de recibir tráfico durante un tiempo. */
public record CircuitOpened(GatewayId gateway, Instant occurredAt) implements GatewayHealthEvent {

    public CircuitOpened {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(occurredAt);
    }
}
