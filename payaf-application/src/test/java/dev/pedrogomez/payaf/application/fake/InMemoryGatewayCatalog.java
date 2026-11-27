package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Catálogo de pasarelas con su perfil y su adaptador. Permite registrar un perfil sin
 * adaptador y colgar un gancho que se ejecuta al buscar el adaptador de una pasarela (el
 * servicio lo busca justo antes de reservar el permiso del breaker), para simular lo que
 * hace otra petición en ese hueco.
 */
public final class InMemoryGatewayCatalog implements GatewayCatalog {

    private final List<GatewayProfile> profiles = new CopyOnWriteArrayList<>();
    private final Map<GatewayId, PaymentGateway> gateways = new ConcurrentHashMap<>();
    private final Map<GatewayId, Runnable> lookupHooks = new ConcurrentHashMap<>();

    public InMemoryGatewayCatalog register(GatewayProfile profile, PaymentGateway gateway) {
        profiles.add(profile);
        gateways.put(profile.id(), gateway);
        return this;
    }

    /** Un perfil configurado sin adaptador registrado. */
    public InMemoryGatewayCatalog registerProfileOnly(GatewayProfile profile) {
        profiles.add(profile);
        return this;
    }

    /** Se ejecuta una vez, la próxima vez que se busque el adaptador de {@code id}. */
    public InMemoryGatewayCatalog onNextLookup(GatewayId id, Runnable hook) {
        lookupHooks.put(id, hook);
        return this;
    }

    @Override
    public List<GatewayProfile> profiles() {
        return List.copyOf(profiles);
    }

    @Override
    public Optional<PaymentGateway> gateway(GatewayId id) {
        Runnable hook = lookupHooks.remove(id);
        if (hook != null) {
            hook.run();
        }
        return Optional.ofNullable(gateways.get(id));
    }
}
