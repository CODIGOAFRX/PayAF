package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.rest.ChaosAdmin;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Implementa el {@link ChaosAdmin} de la REST delegando en la administración del simulador
 * ({@link GatewayChaosControl} y {@link SimulatedGatewayCatalog}). Es un puente entre dos
 * adaptadores, y por eso está en bootstrap: ni la REST conoce el simulador ni al revés.
 */
@Component
class ChaosAdminBridge implements ChaosAdmin {

    private final GatewayChaosControl chaos;
    private final SimulatedGatewayCatalog catalog;

    ChaosAdminBridge(GatewayChaosControl chaos, SimulatedGatewayCatalog catalog) {
        this.chaos = chaos;
        this.catalog = catalog;
    }

    @Override
    public ChaosSettings get(String gateway) {
        FaultConfig config = chaos.snapshot().get(id(gateway));
        if (config == null) {
            throw new UnknownGatewayException(gateway);
        }
        return settings(config);
    }

    @Override
    public ChaosSettings set(String gateway, ChaosSettings settings) {
        GatewayId id = known(gateway);
        if (settings.latencyMs() < 0) {
            throw new IllegalArgumentException("La latencia no puede ser negativa: " + settings.latencyMs());
        }
        chaos.set(id, new FaultConfig(settings.down(), Duration.ofMillis(settings.latencyMs()), settings.errorRate()));
        return get(gateway);
    }

    @Override
    public ChaosSettings reset(String gateway) {
        chaos.reset(known(gateway));
        return get(gateway);
    }

    @Override
    public void setEnabled(String gateway, boolean enabled) {
        catalog.setEnabled(known(gateway), enabled);
    }

    private GatewayId known(String gateway) {
        GatewayId id = id(gateway);
        if (!chaos.snapshot().containsKey(id)) {
            throw new UnknownGatewayException(gateway);
        }
        return id;
    }

    private static GatewayId id(String gateway) {
        try {
            return GatewayId.of(gateway);
        } catch (InvalidValueException e) {
            throw new UnknownGatewayException(gateway);
        }
    }

    private static ChaosSettings settings(FaultConfig config) {
        return new ChaosSettings(config.down(), config.latency().toMillis(), config.errorRate());
    }
}
