package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Administración de las averías del simulador: tirar una pasarela, darle latencia o una tasa
 * de error, y volver a dejarlo todo bien. La usará el panel para enseñar el failover en directo.
 * <p>
 * <strong>No es un puerto de la aplicación.</strong> La aplicación no sabe (ni debe saber) que
 * sus pasarelas son simuladas: para ella solo existe {@code PaymentGateway}. Esto es la API
 * pública de administración de este adaptador concreto, igual que una base de datos tiene su
 * consola de administración aparte del SQL que usa la aplicación. Bootstrap la publica como
 * bean para quien la necesite; si se cambiara el simulador por PSP reales, esta clase
 * desaparecería sin tocar la aplicación.
 * <p>
 * Es segura para hilos: cada {@link FaultInjector} cambia su configuración de forma atómica.
 */
public final class GatewayChaosControl {

    private final Map<GatewayId, FaultInjector> injectors;

    public GatewayChaosControl(Map<GatewayId, FaultInjector> injectors) {
        this.injectors = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(injectors)));
    }

    /**
     * Sustituye las averías de la pasarela.
     *
     * @throws IllegalArgumentException si no hay ninguna pasarela simulada con ese identificador
     */
    public void set(GatewayId id, FaultConfig config) {
        injector(id).configure(config);
    }

    /** Quita las averías de la pasarela. */
    public void reset(GatewayId id) {
        injector(id).reset();
    }

    /** Quita las averías de todas las pasarelas. */
    public void resetAll() {
        injectors.values().forEach(FaultInjector::reset);
    }

    /** Las averías vigentes de cada pasarela, en el orden del catálogo. Es una copia. */
    public Map<GatewayId, FaultConfig> snapshot() {
        Map<GatewayId, FaultConfig> snapshot = new LinkedHashMap<>();
        injectors.forEach((id, injector) -> snapshot.put(id, injector.config()));
        return Collections.unmodifiableMap(snapshot);
    }

    private FaultInjector injector(GatewayId id) {
        Objects.requireNonNull(id, "la pasarela no puede ser nula");
        FaultInjector injector = injectors.get(id);
        if (injector == null) {
            throw new IllegalArgumentException("No hay ninguna pasarela simulada " + id);
        }
        return injector;
    }
}
