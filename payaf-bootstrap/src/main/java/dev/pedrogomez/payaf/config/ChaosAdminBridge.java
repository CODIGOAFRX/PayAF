package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.rest.ChaosAdmin;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.demo.BreakerReset;
import dev.pedrogomez.payaf.demo.SlidingWindowRateLimiter;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.trace.PortTracer;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Implementa el {@link ChaosAdmin} de la REST delegando en la administración del simulador
 * ({@link GatewayChaosControl} y {@link SimulatedGatewayCatalog}) y, para restaurar la demo, en
 * {@link BreakerReset}. Es un puente entre adaptadores, y por eso está en bootstrap: ni la REST
 * conoce el simulador ni al revés.
 */
@Component
class ChaosAdminBridge implements ChaosAdmin {

    private final GatewayChaosControl chaos;
    private final SimulatedGatewayCatalog catalog;
    private final BreakerReset breakerReset;
    private final DemoInfo info;

    ChaosAdminBridge(GatewayChaosControl chaos, SimulatedGatewayCatalog catalog, BreakerReset breakerReset,
                     CircuitBreakerStore breakers, CircuitBreakerConfig breakerConfig, GatewaySimulator simulator,
                     SlidingWindowRateLimiter limiter) {
        this.chaos = chaos;
        this.catalog = catalog;
        this.breakerReset = breakerReset;
        this.info = new DemoInfo(PortTracer.persistenceAdapter(breakers), simulator.timeout().toMillis(),
                breakerConfig.failureThreshold(), breakerConfig.openDuration().toMillis(), limiter.limit());
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

    @Override
    public void resetDemo() {
        chaos.resetAll();
        List<GatewayId> ids = catalog.profiles().stream().map(GatewayProfile::id).toList();
        ids.forEach(id -> catalog.setEnabled(id, true));
        breakerReset.closeAll(ids);
    }

    @Override
    public DemoInfo info() {
        return info;
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
