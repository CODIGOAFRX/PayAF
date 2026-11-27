package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import java.util.Objects;

/**
 * Lo que el panel enseña de una pasarela: sus condiciones (perfil) y el estado de su
 * circuito. Ambos son tipos inmutables del dominio.
 */
public record GatewayHealthView(GatewayProfile profile, CircuitBreakerSnapshot circuit) {

    public GatewayHealthView {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(circuit);
    }
}
