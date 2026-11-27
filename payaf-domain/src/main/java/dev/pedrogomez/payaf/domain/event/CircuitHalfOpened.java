package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Objects;

/** Ha vencido el tiempo de apertura del circuito: la pasarela admite peticiones de prueba. */
public record CircuitHalfOpened(GatewayId gateway, Instant occurredAt) implements GatewayHealthEvent {

    public CircuitHalfOpened {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(occurredAt);
    }
}
