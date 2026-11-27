package dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.authorization;
import static dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.LastPaymentError;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.PaymentIntent;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.StripeException;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StripePaymentGatewayTest {

    private static final String PI_FORMAT = "pi_[A-Za-z0-9]{24}";

    private final StripeClient client = new StripeClient(new Random(11));
    private final FaultInjector faults = new FaultInjector(new Random(1), new RecordingSleeper());
    private final StripePaymentGateway gateway = new StripePaymentGateway(GatewayId.of("stripe"), client, faults);

    private String authorized(long amount) {
        GatewayResponse response = gateway.authorize(authorization(amount));
        assertThat(response.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        return response.gatewayReference().orElseThrow();
    }

    @Test
    @DisplayName("autoriza: requires_capture → APPROVED con un PaymentIntent pi_ + 24 alfanuméricos")
    void approve() {
        String reference = authorized(10_000);

        assertThat(reference).matches(PI_FORMAT);
        assertThat(client.ledger().find(reference)).map(SimulatedLedger.Entry::state)
                .contains(SimulatedLedger.State.AUTHORIZED);
        assertThat(client.ledger().find(reference).orElseThrow().currency()).isEqualTo("eur");
    }

    @Test
    @DisplayName("importe terminado en 51 → DECLINED insufficient_funds (decline_code de card_declined)")
    void decline() {
        GatewayResponse response = gateway.authorize(authorization(10_051));

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(response.code()).contains("insufficient_funds");
        assertThat(client.ledger().size()).isZero();
    }

    @Test
    @DisplayName("la misma autorización con el mismo paymentId (Idempotency-Key) devuelve el mismo PaymentIntent")
    void idempotentAuthorization() {
        GatewayAuthorization auth = authorization(10_000);

        GatewayResponse first = gateway.authorize(auth);
        GatewayResponse second = gateway.authorize(auth);

        assertThat(second.gatewayReference()).isEqualTo(first.gatewayReference());
        assertThat(client.ledger().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("captura, reembolso con referencia re_ y anulación")
    void operations() {
        String reference = authorized(10_000);

        GatewayResponse capture = gateway.capture(reference, eur(10_000));
        assertThat(capture.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(capture.gatewayReference()).contains(reference);

        GatewayResponse refund = gateway.refund(reference, eur(2_500));
        assertThat(refund.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(refund.gatewayReference().orElseThrow()).matches("re_[A-Za-z0-9]{24}");

        String other = authorized(5_000);
        assertThat(gateway.cancel(other).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(client.ledger().find(other).orElseThrow().state()).isEqualTo(SimulatedLedger.State.CANCELED);
    }

    @Test
    @DisplayName("doble captura → DECLINED payment_intent_unexpected_state (la PSP funciona y dice que no)")
    void doubleCapture() {
        String reference = authorized(10_000);
        gateway.capture(reference, eur(10_000));

        GatewayResponse second = gateway.capture(reference, eur(10_000));

        assertThat(second.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(second.code()).contains("payment_intent_unexpected_state");
    }

    @Test
    @DisplayName("el libro valida la referencia y los importes")
    void ledgerValidation() {
        assertThat(gateway.capture("pi_doesnotexist000000000000", eur(100)).code()).contains("resource_missing");

        String reference = authorized(10_000);
        assertThat(gateway.capture(reference, eur(9_999)).code()).contains("parameter_invalid_integer");
        assertThat(gateway.refund(reference, eur(100)).code()).contains("payment_intent_unexpected_state");

        gateway.capture(reference, eur(10_000));
        GatewayResponse tooMuch = gateway.refund(reference, eur(10_001));
        assertThat(tooMuch.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(tooMuch.code()).contains("amount_too_large");
        assertThat(gateway.cancel(reference).outcome()).isEqualTo(AttemptOutcome.DECLINED);
    }

    @Test
    @DisplayName("traducción: 5xx y 429 → ERROR; 4xx → DECLINED; estado inesperado → ERROR")
    void translation() {
        assertThat(StripePaymentGateway.translateError(new StripeException(500, "api_error", "api_error", "x"))
                .outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(StripePaymentGateway.translateError(new StripeException(429, "rate_limit_error", "rate_limit", "x"))
                .outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(StripePaymentGateway.translateError(new StripeException(402, "card_error", "card_declined", "x"))
                .outcome()).isEqualTo(AttemptOutcome.DECLINED);

        PaymentIntent processing = new PaymentIntent("pi_x", 100, "eur", "processing", 0, "manual", null);
        GatewayResponse unexpected = StripePaymentGateway.translateAuthorization(processing);
        assertThat(unexpected.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(unexpected.code()).contains("stripe_unexpected_status:processing");

        PaymentIntent declinedWithoutDeclineCode = new PaymentIntent("pi_x", 100, "eur",
                StripeClient.REQUIRES_PAYMENT_METHOD, 0, "manual",
                new LastPaymentError("card_error", "card_declined", null, "x"));
        assertThat(StripePaymentGateway.translateAuthorization(declinedWithoutDeclineCode).code())
                .contains("card_declined");
    }

    @Test
    @DisplayName("caída → ERROR HTTP_503 y timeout → TIMEOUT, sin tocar el libro")
    void faults() {
        faults.configure(FaultConfig.none().withDown(true));
        GatewayResponse down = gateway.authorize(authorization(10_000));
        assertThat(down.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(down.code()).contains(FaultInjector.HTTP_503);

        faults.configure(FaultConfig.none().withLatency(Duration.ofSeconds(3)));
        assertThat(gateway.authorize(authorization(10_000)).outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(client.ledger().size()).isZero();
    }
}
