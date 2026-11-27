package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog.ADYEN;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog.MOCK;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog.REDSYS;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogAndChaosTest {

    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final GatewaySimulator simulator = GatewaySimulator.create(new Random(3), sleeper);
    private final SimulatedGatewayCatalog catalog = simulator.catalog();
    private final GatewayChaosControl chaos = simulator.chaosControl();

    private GatewayProfile profile(GatewayId id) {
        return catalog.profiles().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("los cuatro perfiles, en orden y con sus condiciones")
    void profiles() {
        assertThat(catalog.profiles()).extracting(GatewayProfile::id).containsExactly(STRIPE, ADYEN, REDSYS, MOCK);
        assertThat(catalog.profiles()).allMatch(GatewayProfile::enabled);

        GatewayProfile stripe = profile(STRIPE);
        assertThat(stripe.currencies()).containsExactlyInAnyOrder(Currency.EUR, Currency.USD, Currency.GBP);
        assertThat(stripe.countries()).isEmpty();
        assertThat(stripe.estimatedFee(Money.of(10_000, Currency.EUR))).isEqualTo(Money.of(315, Currency.EUR));

        GatewayProfile adyen = profile(ADYEN);
        assertThat(adyen.currencies()).contains(Currency.JPY);
        assertThat(adyen.estimatedFee(Money.of(10_000, Currency.EUR))).isEqualTo(Money.of(280, Currency.EUR));
        assertThat(adyen.estimatedFee(Money.of(10_000, Currency.JPY))).isEqualTo(Money.of(250, Currency.JPY));

        GatewayProfile redsys = profile(REDSYS);
        assertThat(redsys.currencies()).containsExactly(Currency.EUR);
        assertThat(redsys.countries()).containsExactly(CountryCode.of("ES"));
        assertThat(redsys.estimatedFee(Money.of(10_000, Currency.EUR))).isEqualTo(Money.of(120, Currency.EUR));

        GatewayProfile mock = profile(MOCK);
        assertThat(mock.currencies()).isEqualTo(EnumSet.allOf(Currency.class));
        assertThat(mock.countries()).isEmpty();
        assertThat(mock.feeBasisPoints()).isEqualTo(900);
    }

    @Test
    @DisplayName("cada perfil tiene su adaptador, con el mismo identificador")
    void gateways() {
        for (GatewayProfile profile : catalog.profiles()) {
            assertThat(catalog.gateway(profile.id())).map(PaymentGateway::id).contains(profile.id());
        }
        assertThat(catalog.gateway(GatewayId.of("paypal"))).isEmpty();
    }

    @Test
    @DisplayName("setEnabled saca y vuelve a meter una pasarela")
    void setEnabled() {
        catalog.setEnabled(REDSYS, false);
        assertThat(profile(REDSYS).enabled()).isFalse();
        assertThat(catalog.gateway(REDSYS)).isPresent();

        catalog.setEnabled(REDSYS, true);
        assertThat(profile(REDSYS).enabled()).isTrue();
        assertThatThrownBy(() -> catalog.setEnabled(GatewayId.of("paypal"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un adaptador sin perfil o repetido no se admite")
    void rejectsUnknownGateways() {
        PaymentGateway unknown = new dev.pedrogomez.payaf.adapter.out.gatewaysim.mock.MockPaymentGateway(
                GatewayId.of("paypal"), new dev.pedrogomez.payaf.adapter.out.gatewaysim.mock.MockPsp(),
                SimFixtures.quietInjector());
        assertThatThrownBy(() -> new SimulatedGatewayCatalog(List.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class);
        PaymentGateway mock = catalog.gateway(MOCK).orElseThrow();
        assertThatThrownBy(() -> new SimulatedGatewayCatalog(List.of(mock, mock)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la ChaosControl tira una pasarela concreta y la vuelve a levantar")
    void chaosControl() {
        PaymentGateway redsys = catalog.gateway(REDSYS).orElseThrow();
        PaymentGateway adyen = catalog.gateway(ADYEN).orElseThrow();

        chaos.set(REDSYS, FaultConfig.none().withDown(true));

        GatewayResponse down = redsys.authorize(SimFixtures.authorization(10_000));
        assertThat(down.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(down.code()).contains(FaultInjector.HTTP_503);
        assertThat(adyen.authorize(SimFixtures.authorization(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(chaos.snapshot().get(REDSYS).down()).isTrue();
        assertThat(chaos.snapshot().get(ADYEN)).isEqualTo(FaultConfig.none());

        chaos.reset(REDSYS);
        assertThat(redsys.authorize(SimFixtures.authorization(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
    }

    @Test
    @DisplayName("resetAll y snapshot en el orden del catálogo; una pasarela desconocida es un error")
    void resetAllAndSnapshot() {
        chaos.set(STRIPE, FaultConfig.none().withLatency(Duration.ofMillis(100)));
        chaos.set(MOCK, FaultConfig.none().withErrorRate(0.5));

        Map<GatewayId, FaultConfig> snapshot = chaos.snapshot();
        assertThat(snapshot.keySet()).containsExactly(STRIPE, ADYEN, REDSYS, MOCK);
        assertThat(snapshot.get(STRIPE).latency()).isEqualTo(Duration.ofMillis(100));
        assertThatThrownBy(() -> snapshot.put(STRIPE, FaultConfig.none()))
                .isInstanceOf(UnsupportedOperationException.class);

        chaos.resetAll();
        assertThat(Set.copyOf(chaos.snapshot().values())).containsExactly(FaultConfig.none());

        assertThatThrownBy(() -> chaos.set(GatewayId.of("paypal"), FaultConfig.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.reset(GatewayId.of("paypal"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una latencia por encima del timeout da TIMEOUT de 2 s sin dormir de verdad")
    void timeoutThroughSimulator() {
        chaos.set(STRIPE, FaultConfig.none().withLatency(Duration.ofSeconds(10)));

        GatewayResponse response = catalog.gateway(STRIPE).orElseThrow().authorize(SimFixtures.authorization(10_000));

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(response.latency()).isEqualTo(Duration.ofSeconds(2));
        assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(2));
        assertThat(simulator.stripe().ledger().size()).isZero();
    }
}
