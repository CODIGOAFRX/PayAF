package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.Sleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.WebhookSecrets;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Las pasarelas de pago: de momento, las cuatro PSP simuladas (Stripe, Adyen, Redsys y mock).
 * <p>
 * Publica dos beans: el {@link SimulatedGatewayCatalog}, que es el puerto {@code GatewayCatalog}
 * que usan los casos de uso, y la {@link GatewayChaosControl}, que es la administración de las
 * averías del simulador para el panel (no es un puerto de la aplicación). Las latencias se
 * duermen de verdad ({@link Sleeper#system()}).
 * <p>
 * Con {@code payaf.sim.webhooks.enabled=true} (lo normal en {@code memory} y {@code postgres}),
 * Stripe, Adyen y Redsys emiten además un webhook firmado tras cada captura y reembolso, que
 * {@link SimulatedWebhookDispatcher} envía a {@code /webhooks/{gateway}}.
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySimConfig {

    @Bean
    GatewaySimulator gatewaySimulator(ObjectProvider<SimulatedWebhookDispatcher> dispatcher,
                                      WebhookProperties properties, Clock clock) {
        SimulatedWebhookDispatcher sink = dispatcher.getIfAvailable();
        if (sink == null) {
            return GatewaySimulator.create(new SecureRandom(), Sleeper.system());
        }
        WebhookProperties.Secrets s = properties.secrets();
        return GatewaySimulator.create(new SecureRandom(), Sleeper.system(), new GatewaySimulator.Webhooks(sink,
                new WebhookSecrets(s.stripe(), s.adyen(), s.redsys()), clock));
    }

    @Bean
    @ConditionalOnProperty(name = "payaf.sim.webhooks.enabled", havingValue = "true")
    SimulatedWebhookDispatcher simulatedWebhookDispatcher() {
        return new SimulatedWebhookDispatcher();
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
