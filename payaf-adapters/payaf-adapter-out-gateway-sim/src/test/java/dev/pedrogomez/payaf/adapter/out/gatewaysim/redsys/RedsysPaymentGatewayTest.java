package dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.authorization;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysRequest;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysResponse;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedsysPaymentGatewayTest {

    private final RedsysClient client = new RedsysClient(new Random(7));
    private final FaultInjector faults = new FaultInjector(new Random(1), new RecordingSleeper());
    private final RedsysPaymentGateway gateway = new RedsysPaymentGateway(GatewayId.of("redsys"), client, faults,
            new Random(9));

    private String authorized(long amount) {
        GatewayResponse response = gateway.authorize(authorization(amount));
        assertThat(response.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        return response.gatewayReference().orElseThrow();
    }

    private static RedsysResponse response(String dsResponse, String type) {
        return new RedsysResponse("1234ABCDEFGH", "100", "978", dsResponse, "123456", type, null);
    }

    @Test
    @DisplayName("preautorización 0000 → APPROVED; la referencia es el Ds_Order de 12 caracteres")
    void approve() {
        String order = authorized(10_000);

        assertThat(order).hasSize(12).matches("[0-9]{4}[A-Z0-9]{8}");
        SimulatedLedger.Entry entry = client.ledger().find(order).orElseThrow();
        assertThat(entry.amountMinor()).isEqualTo(10_000);
        assertThat(entry.currency()).isEqualTo("978");
    }

    @Test
    @DisplayName("el cliente devuelve un Ds_AuthorisationCode de 6 dígitos")
    void authorisationCode() {
        RedsysResponse response = client.process(new RedsysRequest("0001ABCDEFGH", "10000", "978",
                RedsysClient.PREAUTHORIZATION, "999008881", "1"));

        assertThat(response.dsResponse()).isEqualTo("0000");
        assertThat(response.dsAuthorisationCode()).matches("[0-9]{6}");
    }

    @Test
    @DisplayName("importe terminado en 51 → Ds_Response 0190 → DECLINED 0190")
    void decline() {
        GatewayResponse response = gateway.authorize(authorization(10_051));

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(response.code()).contains("0190");
        assertThat(client.ledger().size()).isZero();
    }

    @Test
    @DisplayName("confirmación (0900), devolución (0900) y anulación (0400) → APPROVED")
    void operations() {
        String order = authorized(10_000);

        GatewayResponse capture = gateway.capture(order, eur(10_000));
        assertThat(capture.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(capture.gatewayReference()).contains(order);

        GatewayResponse refund1 = gateway.refund(order, eur(1_000));
        GatewayResponse refund2 = gateway.refund(order, eur(1_000));
        assertThat(refund1.gatewayReference().orElseThrow()).matches(order + "-[0-9]{6}");
        assertThat(refund2.gatewayReference()).isNotEqualTo(refund1.gatewayReference());

        String other = authorized(3_000);
        assertThat(gateway.cancel(other).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(client.ledger().find(other).orElseThrow().state()).isEqualTo(SimulatedLedger.State.CANCELED);
    }

    @Test
    @DisplayName("doble confirmación → DECLINED SIS0063; pedido desconocido → SIS0072; importe → SIS0062 y SIS0057")
    void ledgerRejections() {
        String order = authorized(10_000);
        assertThat(gateway.capture(order, eur(10_001)).code()).contains("SIS0062");
        gateway.capture(order, eur(10_000));

        GatewayResponse second = gateway.capture(order, eur(10_000));
        assertThat(second.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(second.code()).contains("SIS0063");

        assertThat(gateway.capture("9999ZZZZZZZZ", eur(100)).code()).contains("SIS0072");
        assertThat(gateway.refund(order, eur(10_001)).code()).contains("SIS0057");
    }

    @Test
    @DisplayName("pedido repetido → SIS0051; número de pedido mal formado → SIS0075")
    void orderValidation() {
        RedsysRequest request = new RedsysRequest("0001ABCDEFGH", "10000", "978", RedsysClient.PREAUTHORIZATION,
                "999008881", "1");
        client.process(request);

        assertThat(client.process(request).errorCode()).isEqualTo("SIS0051");
        assertThat(client.process(new RedsysRequest("ABCD12345678", "10000", "978", RedsysClient.PREAUTHORIZATION,
                "999008881", "1")).errorCode()).isEqualTo("SIS0075");
    }

    @Test
    @DisplayName("traducción de Ds_Response: 0000-0099 OK, 0100-0999 denegada, 9xxx error, ilegible error")
    void translation() {
        assertThat(RedsysPaymentGateway.translate(response("0099", "1"), false).outcome())
                .isEqualTo(AttemptOutcome.APPROVED);
        assertThat(RedsysPaymentGateway.translate(response("0100", "1"), false).outcome())
                .isEqualTo(AttemptOutcome.DECLINED);
        // 0900 solo es «aceptada» en confirmaciones y devoluciones, no en una preautorización.
        assertThat(RedsysPaymentGateway.translate(response("0900", "1"), false).outcome())
                .isEqualTo(AttemptOutcome.DECLINED);
        assertThat(RedsysPaymentGateway.translate(response("0900", "2"), false).outcome())
                .isEqualTo(AttemptOutcome.APPROVED);

        GatewayResponse error = RedsysPaymentGateway.translate(response("9912", "1"), false);
        assertThat(error.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(error.code()).contains("9912");

        assertThat(RedsysPaymentGateway.translate(response("abc", "1"), false).outcome())
                .isEqualTo(AttemptOutcome.ERROR);
        assertThat(RedsysPaymentGateway.translate(response(null, "1"), false).outcome())
                .isEqualTo(AttemptOutcome.ERROR);
    }

    @Test
    @DisplayName("divisas con su código numérico ISO 4217")
    void numericCurrencies() {
        assertThat(RedsysPaymentGateway.numericCurrency(Currency.EUR)).isEqualTo("978");
        assertThat(RedsysPaymentGateway.numericCurrency(Currency.USD)).isEqualTo("840");
        assertThat(RedsysPaymentGateway.numericCurrency(Currency.GBP)).isEqualTo("826");
        assertThat(RedsysPaymentGateway.numericCurrency(Currency.JPY)).isEqualTo("392");
    }

    @Test
    @DisplayName("caída → ERROR HTTP_503; latencia excesiva → TIMEOUT; errorRate 1 → ERROR HTTP_500")
    void faults() {
        faults.configure(FaultConfig.none().withDown(true));
        assertThat(gateway.authorize(authorization(10_000)).code()).contains(FaultInjector.HTTP_503);

        faults.configure(FaultConfig.none().withLatency(Duration.ofMillis(2_001)));
        assertThat(gateway.authorize(authorization(10_000)).outcome()).isEqualTo(AttemptOutcome.TIMEOUT);

        faults.configure(FaultConfig.none().withErrorRate(1.0));
        assertThat(gateway.authorize(authorization(10_000)).code()).contains(FaultInjector.HTTP_500);
        assertThat(client.ledger().size()).isZero();
    }
}
