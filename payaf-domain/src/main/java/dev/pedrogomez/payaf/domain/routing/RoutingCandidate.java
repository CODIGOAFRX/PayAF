package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Objects;

/**
 * Pasarela apta para un pago, con la comisión que costaría y el estado de su circuito en
 * el momento de decidir (CLOSED o HALF_OPEN).
 */
public record RoutingCandidate(GatewayId gateway, Money estimatedFee, CircuitState circuitState) {

    public RoutingCandidate {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(estimatedFee);
        Objects.requireNonNull(circuitState);
    }
}
