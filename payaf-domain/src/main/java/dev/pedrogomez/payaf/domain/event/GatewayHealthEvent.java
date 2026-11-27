package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.shared.GatewayId;

/**
 * Evento de salud de una pasarela: su circuit breaker ha cambiado de estado. Lo emite
 * {@link dev.pedrogomez.payaf.domain.routing.CircuitBreaker} y no pertenece a ningún pago.
 */
public sealed interface GatewayHealthEvent extends DomainEvent
        permits CircuitOpened, CircuitHalfOpened, CircuitClosed {

    GatewayId gateway();
}
