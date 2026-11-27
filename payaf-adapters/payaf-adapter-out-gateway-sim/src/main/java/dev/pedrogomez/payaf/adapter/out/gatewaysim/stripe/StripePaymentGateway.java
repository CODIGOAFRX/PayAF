package dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.PaymentIntent;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.Refund;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe.StripeClient.StripeException;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adaptador del puerto {@link PaymentGateway} para Stripe: traduce entre el idioma de la
 * aplicación ({@link Money}, {@link GatewayResponse}) y el de Stripe ({@link StripeClient}).
 * <p>
 * Traducción (capa anticorrupción):
 * <ul>
 *   <li>Importes: unidades menores y divisa ISO en minúsculas ({@code eur}), como pide Stripe.</li>
 *   <li>Autorizar: {@code requires_capture} → APPROVED con el id {@code pi_...};
 *       {@code requires_payment_method} con {@code last_payment_error} → DECLINED con el
 *       {@code decline_code} (o el {@code code} si no hay), p. ej. {@code insufficient_funds}.</li>
 *   <li>Capturar {@code succeeded}, anular {@code canceled} → APPROVED con el id del
 *       PaymentIntent; reembolsar {@code succeeded} → APPROVED con el id {@code re_...} del
 *       reembolso.</li>
 *   <li>{@link StripeException} 4xx (salvo 429) → DECLINED con su {@code code}: Stripe ha
 *       funcionado y dice que no (capturar dos veces da {@code payment_intent_unexpected_state}).
 *       429 y 5xx → ERROR con el código, porque son fallos técnicos.</li>
 *   <li>Cualquier estado inesperado → ERROR {@code stripe_unexpected_status:<estado>}.</li>
 * </ul>
 * El {@link GatewayAuthorization#paymentId()} viaja como {@code Idempotency-Key}: reintentar la
 * autorización del mismo pago en Stripe devuelve el mismo PaymentIntent y no retiene dos veces.
 * Todas las llamadas pasan por el {@link FaultInjector} de la pasarela. Nunca lanza.
 */
public final class StripePaymentGateway implements PaymentGateway {

    private final GatewayId id;
    private final StripeClient client;
    private final FaultInjector faults;

    public StripePaymentGateway(GatewayId id, StripeClient client, FaultInjector faults) {
        this.id = Objects.requireNonNull(id);
        this.client = Objects.requireNonNull(client);
        this.faults = Objects.requireNonNull(faults);
    }

    @Override
    public GatewayId id() {
        return id;
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        return faults.call(() -> translating(() -> translateAuthorization(client.createPaymentIntent(
                authorization.amount().amountMinor(), currency(authorization.amount()),
                authorization.paymentId().toString()))));
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return faults.call(() -> translating(() -> {
            PaymentIntent intent = client.capturePaymentIntent(gatewayReference, amount.amountMinor());
            return expectStatus(intent, StripeClient.SUCCEEDED);
        }));
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return faults.call(() -> translating(() -> {
            Refund refund = client.createRefund(gatewayReference, amount.amountMinor(), currency(amount));
            return StripeClient.SUCCEEDED.equals(refund.status())
                    ? GatewayResponse.approved(refund.id(), Duration.ZERO)
                    : GatewayResponse.declined("refund_" + refund.status(), Duration.ZERO);
        }));
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return faults.call(() -> translating(() -> expectStatus(client.cancelPaymentIntent(gatewayReference),
                StripeClient.CANCELED)));
    }

    /** Traduce el PaymentIntent recién creado y confirmado. */
    static GatewayResponse translateAuthorization(PaymentIntent intent) {
        if (StripeClient.REQUIRES_CAPTURE.equals(intent.status())) {
            return GatewayResponse.approved(intent.id(), Duration.ZERO);
        }
        if (StripeClient.REQUIRES_PAYMENT_METHOD.equals(intent.status()) && intent.lastPaymentError() != null) {
            StripeClient.LastPaymentError error = intent.lastPaymentError();
            return GatewayResponse.declined(error.declineCode() != null ? error.declineCode() : error.code(),
                    Duration.ZERO);
        }
        return unexpected(intent.status());
    }

    /** Traduce un error de la API de Stripe. */
    static GatewayResponse translateError(StripeException e) {
        int status = e.httpStatus();
        if (status >= 400 && status < 500 && status != 429) {
            return GatewayResponse.declined(e.code(), Duration.ZERO);
        }
        return GatewayResponse.error(e.code() != null ? e.code() : "HTTP_" + status, Duration.ZERO);
    }

    private static GatewayResponse expectStatus(PaymentIntent intent, String expected) {
        return expected.equals(intent.status())
                ? GatewayResponse.approved(intent.id(), Duration.ZERO)
                : unexpected(intent.status());
    }

    private static GatewayResponse unexpected(String status) {
        return GatewayResponse.error("stripe_unexpected_status:" + status, Duration.ZERO);
    }

    private static GatewayResponse translating(Supplier<GatewayResponse> call) {
        try {
            return call.get();
        } catch (StripeException e) {
            return translateError(e);
        }
    }

    private static String currency(Money amount) {
        return amount.currency().name().toLowerCase(Locale.ROOT);
    }
}
