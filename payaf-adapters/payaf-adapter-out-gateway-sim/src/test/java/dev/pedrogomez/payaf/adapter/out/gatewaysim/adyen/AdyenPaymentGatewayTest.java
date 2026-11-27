package dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.authorization;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.AdyenServiceException;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.Amount;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.ModificationResponse;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.PaymentResponse;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdyenPaymentGatewayTest {

    private static final String PSP_REFERENCE = "[1-9][0-9]{15}";

    private final AdyenClient client = new AdyenClient(new Random(5));
    private final FaultInjector faults = new FaultInjector(new Random(1), new RecordingSleeper());
    private final AdyenPaymentGateway gateway = new AdyenPaymentGateway(GatewayId.of("adyen"), client, faults);

    private String authorized(long amount) {
        GatewayResponse response = gateway.authorize(authorization(amount));
        assertThat(response.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        return response.gatewayReference().orElseThrow();
    }

    @Test
    @DisplayName("Authorised → APPROVED con un pspReference de 16 dígitos")
    void approve() {
        String reference = authorized(10_000);

        assertThat(reference).matches(PSP_REFERENCE);
        assertThat(client.ledger().find(reference).orElseThrow().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("JPY va sin decimales, tal cual en unidades menores")
    void yen() {
        GatewayResponse response = gateway.authorize(authorization(1_500, Currency.JPY));
        String reference = response.gatewayReference().orElseThrow();

        assertThat(client.ledger().find(reference).orElseThrow().amountMinor()).isEqualTo(1_500);
        assertThat(gateway.capture(reference, Money.of(1_500, Currency.JPY)).outcome())
                .isEqualTo(AttemptOutcome.APPROVED);
    }

    @Test
    @DisplayName("importe terminado en 51 → Refused → DECLINED «Not enough balance»")
    void decline() {
        GatewayResponse response = gateway.authorize(authorization(10_051));

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(response.code()).contains("Not enough balance");
        assertThat(client.ledger().size()).isZero();
    }

    @Test
    @DisplayName("reintentar el mismo pago (Idempotency-Key) no autoriza dos veces")
    void idempotentAuthorization() {
        GatewayAuthorization auth = authorization(10_000);

        assertThat(gateway.authorize(auth).gatewayReference()).isEqualTo(gateway.authorize(auth).gatewayReference());
        assertThat(client.ledger().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("captura, reembolso y anulación: received → APPROVED con el pspReference de la modificación")
    void modifications() {
        String reference = authorized(10_000);

        GatewayResponse capture = gateway.capture(reference, eur(10_000));
        assertThat(capture.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(capture.gatewayReference().orElseThrow()).matches(PSP_REFERENCE).isNotEqualTo(reference);

        GatewayResponse refund = gateway.refund(reference, eur(1_000));
        assertThat(refund.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(refund.gatewayReference().orElseThrow()).matches(PSP_REFERENCE).isNotEqualTo(reference);
        assertThat(client.ledger().find(reference).orElseThrow().refundedMinor()).isEqualTo(1_000);

        String other = authorized(2_000);
        assertThat(gateway.cancel(other).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(client.ledger().find(other).orElseThrow().state()).isEqualTo(SimulatedLedger.State.CANCELED);
    }

    @Test
    @DisplayName("doble captura → DECLINED 168; referencia desconocida → 167; importe que no cuadra → 137")
    void ledgerRejections() {
        String reference = authorized(10_000);
        assertThat(gateway.capture(reference, eur(10_001)).code()).contains("137");
        gateway.capture(reference, eur(10_000));

        GatewayResponse second = gateway.capture(reference, eur(10_000));
        assertThat(second.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(second.code()).contains("168");

        assertThat(gateway.capture("1234567890123456", eur(100)).code()).contains("167");
        assertThat(gateway.refund(reference, eur(20_000)).code()).contains("138");
    }

    @Test
    @DisplayName("traducción: Error → ERROR, resultado desconocido → ERROR, 5xx → ERROR, 422 → DECLINED")
    void translation() {
        Amount amount = new Amount("EUR", 100);
        GatewayResponse error = AdyenPaymentGateway.translatePayment(
                new PaymentResponse("1234567890123456", AdyenClient.ERROR, "Acquirer Error", "4", "ref", amount));
        assertThat(error.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(error.code()).contains("Acquirer Error");

        assertThat(AdyenPaymentGateway.translatePayment(
                new PaymentResponse("1234567890123456", "Pending", null, null, "ref", amount)).outcome())
                .isEqualTo(AttemptOutcome.ERROR);
        assertThat(AdyenPaymentGateway.translateModification(
                new ModificationResponse("1", "2", "failed", amount)).outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(AdyenPaymentGateway.translateError(new AdyenServiceException(500, "901", "x", "internal"))
                .outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(AdyenPaymentGateway.translateError(new AdyenServiceException(422, "137", "x", "validation"))
                .outcome()).isEqualTo(AttemptOutcome.DECLINED);
    }

    @Test
    @DisplayName("caída → ERROR HTTP_503; latencia excesiva → TIMEOUT")
    void faults() {
        faults.configure(FaultConfig.none().withDown(true));
        assertThat(gateway.authorize(authorization(10_000)).code()).contains(FaultInjector.HTTP_503);

        faults.configure(FaultConfig.none().withLatency(Duration.ofSeconds(4)));
        assertThat(gateway.authorize(authorization(10_000)).outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
    }
}
