package dev.pedrogomez.payaf.adapter.out.gatewaysim.mock;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.authorization;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

    private final MockPsp psp = new MockPsp();
    private final FaultInjector faults = SimFixtures.quietInjector();
    private final MockPaymentGateway gateway = new MockPaymentGateway(GatewayId.of("mock"), psp, faults);

    @Test
    @DisplayName("OK → APPROVED con referencias mock_ correlativas, en cualquier divisa")
    void approve() {
        assertThat(gateway.authorize(authorization(10_000)).gatewayReference()).contains("mock_000001");
        assertThat(gateway.authorize(authorization(700, Currency.JPY)).gatewayReference()).contains("mock_000002");
    }

    @Test
    @DisplayName("importe terminado en 51 → KO → DECLINED mock_decline_51")
    void decline() {
        GatewayResponse response = gateway.authorize(authorization(10_051));

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(response.code()).contains(MockPsp.DECLINE_REASON);
    }

    @Test
    @DisplayName("captura, reembolso con referencia propia, doble captura DECLINED y anulación")
    void operations() {
        String reference = gateway.authorize(authorization(10_000)).gatewayReference().orElseThrow();

        assertThat(gateway.capture(reference, eur(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        GatewayResponse twice = gateway.capture(reference, eur(10_000));
        assertThat(twice.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(twice.code()).contains("INVALID_STATE");

        assertThat(gateway.refund(reference, eur(100)).gatewayReference()).contains(reference + "-r1");
        assertThat(gateway.refund(reference, eur(100)).gatewayReference()).contains(reference + "-r2");
        assertThat(gateway.refund(reference, eur(10_000)).code()).contains("REFUND_EXCEEDS_CAPTURED");
        assertThat(gateway.cancel("mock_999999").code()).contains("UNKNOWN_REFERENCE");
    }

    @Test
    @DisplayName("traducción: estado desconocido → ERROR; caída → ERROR HTTP_503")
    void translationAndFaults() {
        assertThat(MockPaymentGateway.translate(new MockPsp.MockReply("MAYBE", null, null)).outcome())
                .isEqualTo(AttemptOutcome.ERROR);

        faults.configure(FaultConfig.none().withDown(true));
        assertThat(gateway.authorize(authorization(10_000)).code()).contains(FaultInjector.HTTP_503);
    }
}
