package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.in.QueryGatewayHealthUseCase;
import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Salud de las pasarelas para el panel.
 * <p>
 * Es de solo lectura: usa {@link CircuitBreaker#snapshotAt(Instant)}, que calcula el estado
 * efectivo en {@code now} sin hacer avanzar el circuito ni emitir eventos. Así un circuito
 * OPEN cuyo tiempo de apertura ha vencido se muestra HALF_OPEN, y en HALF_OPEN no cuentan
 * las pruebas caducadas: es lo que encontrará la próxima petición. El breaker real no cambia
 * hasta entonces. La regla vive en el dominio, junto a la de {@code allowsRequest}, para que
 * las dos no puedan divergir.
 */
public final class QueryGatewayHealthService implements QueryGatewayHealthUseCase {

    private final GatewayCatalog catalog;
    private final CircuitBreakerStore breakerStore;

    public QueryGatewayHealthService(GatewayCatalog catalog, CircuitBreakerStore breakerStore) {
        this.catalog = Objects.requireNonNull(catalog);
        this.breakerStore = Objects.requireNonNull(breakerStore);
    }

    @Override
    public List<GatewayHealthView> health(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        List<GatewayHealthView> views = new ArrayList<>();
        for (GatewayProfile profile : catalog.profiles()) {
            CircuitBreaker breaker = breakerStore.get(profile.id());
            CircuitBreakerSnapshot snapshot;
            synchronized (breaker) {
                snapshot = breaker.snapshotAt(now);
            }
            views.add(new GatewayHealthView(profile, snapshot));
        }
        return List.copyOf(views);
    }
}
