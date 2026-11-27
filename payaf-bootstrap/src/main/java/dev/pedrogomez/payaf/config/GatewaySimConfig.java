package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.Sleeper;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Las pasarelas de pago: de momento, las cuatro PSP simuladas (Stripe, Adyen, Redsys y mock).
 * <p>
 * Publica dos beans: el {@link SimulatedGatewayCatalog}, que es el puerto {@code GatewayCatalog}
 * que usan los casos de uso, y la {@link GatewayChaosControl}, que es la administración de las
 * averías del simulador para el panel (no es un puerto de la aplicación). Las latencias se
 * duermen de verdad ({@link Sleeper#system()}).
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySimConfig {

    @Bean
    GatewaySimulator gatewaySimulator() {
        return GatewaySimulator.create(new SecureRandom(), Sleeper.system());
    }

    @Bean
    SimulatedGatewayCatalog gatewayCatalog(GatewaySimulator simulator) {
        return simulator.catalog();
    }

    @Bean
    GatewayChaosControl gatewayChaosControl(GatewaySimulator simulator) {
        return simulator.chaosControl();
    }
}
