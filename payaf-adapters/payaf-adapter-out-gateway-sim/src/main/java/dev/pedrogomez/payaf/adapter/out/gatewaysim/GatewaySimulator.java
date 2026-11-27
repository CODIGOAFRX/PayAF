package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenPaymentGateway;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenWebhooks;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.mock.MockPaymentGateway;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.mock.MockPsp;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysPaymentGateway;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysWebhooks;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripePaymentGateway;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeWebhooks;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.WebhookEmittingGateway;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.WebhookSecrets;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.WebhookSink;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Monta el simulador completo: las cuatro PSP falsas, sus adaptadores, un
 * {@link FaultInjector} por pasarela, el {@link SimulatedGatewayCatalog} y la
 * {@link GatewayChaosControl}. Es lo que usa bootstrap; así el cableado interno del adaptador no
 * se reparte por la configuración de Spring.
 */
public final class GatewaySimulator {

    private final StripeClient stripe;
    private final AdyenClient adyen;
    private final RedsysClient redsys;
    private final MockPsp mock;
    private final SimulatedGatewayCatalog catalog;
    private final GatewayChaosControl chaosControl;
    private final Duration timeout;

    private GatewaySimulator(Random random, Sleeper sleeper, Duration timeout, Webhooks webhooks) {
        Objects.requireNonNull(random);
        Objects.requireNonNull(sleeper);
        this.timeout = Objects.requireNonNull(timeout);
        stripe = new StripeClient(random);
        adyen = new AdyenClient(random);
        redsys = new RedsysClient(random);
        mock = new MockPsp();

        Map<GatewayId, FaultInjector> injectors = new LinkedHashMap<>();
        for (GatewayId id : List.of(SimulatedGatewayCatalog.STRIPE, SimulatedGatewayCatalog.ADYEN,
                SimulatedGatewayCatalog.REDSYS, SimulatedGatewayCatalog.MOCK)) {
            injectors.put(id, new FaultInjector(timeout, random, sleeper));
        }
        PaymentGateway stripeGateway = new StripePaymentGateway(SimulatedGatewayCatalog.STRIPE, stripe,
                injectors.get(SimulatedGatewayCatalog.STRIPE));
        PaymentGateway adyenGateway = new AdyenPaymentGateway(SimulatedGatewayCatalog.ADYEN, adyen,
                injectors.get(SimulatedGatewayCatalog.ADYEN));
        PaymentGateway redsysGateway = new RedsysPaymentGateway(SimulatedGatewayCatalog.REDSYS, redsys,
                injectors.get(SimulatedGatewayCatalog.REDSYS), random);
        if (webhooks != null) {
            WebhookSecrets secrets = webhooks.secrets();
            stripeGateway = new WebhookEmittingGateway(stripeGateway,
                    new StripeWebhooks(secrets.stripe(), webhooks.clock(), random), webhooks.sink());
            adyenGateway = new WebhookEmittingGateway(adyenGateway,
                    new AdyenWebhooks(secrets.adyen(), webhooks.clock()), webhooks.sink());
            redsysGateway = new WebhookEmittingGateway(redsysGateway,
                    new RedsysWebhooks(secrets.redsys(), webhooks.clock()), webhooks.sink());
        }
        catalog = new SimulatedGatewayCatalog(List.of(stripeGateway, adyenGateway, redsysGateway,
                new MockPaymentGateway(SimulatedGatewayCatalog.MOCK, mock,
                        injectors.get(SimulatedGatewayCatalog.MOCK))));
        chaosControl = new GatewayChaosControl(injectors);
    }

    /**
     * Webhooks que emiten las PSP simuladas tras una captura o un reembolso aprobados (mock no
     * emite ninguno, porque no hay PSP real que imitar).
     *
     * @param sink    adónde se dejan
     * @param secrets con qué firma cada PSP
     * @param clock   el reloj de las firmas y fechas
     */
    public record Webhooks(WebhookSink sink, WebhookSecrets secrets, Clock clock) {

        public Webhooks {
            Objects.requireNonNull(sink);
            Objects.requireNonNull(secrets);
            Objects.requireNonNull(clock);
        }
    }

    /** Con el timeout por defecto de cada pasarela ({@link FaultInjector#DEFAULT_TIMEOUT}). */
    public static GatewaySimulator create(Random random, Sleeper sleeper) {
        return new GatewaySimulator(random, sleeper, FaultInjector.DEFAULT_TIMEOUT, null);
    }

    public static GatewaySimulator create(Random random, Sleeper sleeper, Duration timeout) {
        return new GatewaySimulator(random, sleeper, timeout, null);
    }

    /** Con el timeout por defecto y webhooks firmados tras cada captura y reembolso. */
    public static GatewaySimulator create(Random random, Sleeper sleeper, Webhooks webhooks) {
        return new GatewaySimulator(random, sleeper, FaultInjector.DEFAULT_TIMEOUT, Objects.requireNonNull(webhooks));
    }

    /** Timeout de cada pasarela simulada: lo más que puede tardar una llamada. */
    public Duration timeout() {
        return timeout;
    }

    public SimulatedGatewayCatalog catalog() {
        return catalog;
    }

    public GatewayChaosControl chaosControl() {
        return chaosControl;
    }

    public StripeClient stripe() {
        return stripe;
    }

    public AdyenClient adyen() {
        return adyen;
    }

    public RedsysClient redsys() {
        return redsys;
    }

    public MockPsp mock() {
        return mock;
    }
}
