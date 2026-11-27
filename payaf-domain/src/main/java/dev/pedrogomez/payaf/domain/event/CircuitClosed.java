package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Objects;

/** La prueba ha ido bien y el circuito se ha cerrado: la pasarela vuelve a recibir tráfico normal. */
public record CircuitClosed(GatewayId gateway, Instant occurredAt) implements GatewayHealthEvent {

    public CircuitClosed {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(occurredAt);
    }
}
