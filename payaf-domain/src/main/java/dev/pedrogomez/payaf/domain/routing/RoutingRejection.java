package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Objects;

/** Pasarela descartada para un pago y el motivo. */
public record RoutingRejection(GatewayId gateway, RejectionReason reason) {

    public RoutingRejection {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(reason);
    }
}
