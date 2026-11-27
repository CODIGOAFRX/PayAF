package dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.AdyenServiceException;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.Amount;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.ModificationResponse;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.PaymentRequest;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen.AdyenClient.PaymentResponse;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adaptador del puerto {@link PaymentGateway} para Adyen: traduce entre el idioma de la
 * aplicación y el de la Checkout API ({@link AdyenClient}).
 * <p>
 * Traducción (capa anticorrupción):
 * <ul>
 *   <li>Importes: {@code Amount(currency, value)} con la divisa ISO en mayúsculas y el valor en
 *       unidades menores (el JPY, sin decimales, va tal cual).</li>
 *   <li>{@code resultCode} {@code Authorised} → APPROVED con el {@code pspReference};
 *       {@code Refused} → DECLINED con el {@code refusalReason} (p. ej.
 *       {@code "Not enough balance"}); {@code Error} o cualquier otro → ERROR.</li>
 *   <li>Modificación {@code received} → APPROVED con el {@code pspReference} de la
 *       modificación: en un reembolso, es la referencia del reembolso que exige el puerto.</li>
 *   <li>{@link AdyenServiceException} 4xx (salvo 429) → DECLINED con el {@code errorCode}
 *       (capturar dos veces da {@code 168}); 429 y 5xx → ERROR.</li>
 * </ul>
 * El {@code paymentId} viaja como {@code reference} del comercio y como {@code Idempotency-Key}.
 * Todas las llamadas pasan por el {@link FaultInjector}. Nunca lanza.
 */
public final class AdyenPaymentGateway implements PaymentGateway {

    /** Cuenta de comercio con la que se habla con Adyen (en real, sale de la configuración). */
    static final String MERCHANT_ACCOUNT = "PayAFECOM";

    private final GatewayId id;
    private final AdyenClient client;
    private final FaultInjector faults;

    public AdyenPaymentGateway(GatewayId id, AdyenClient client, FaultInjector faults) {
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
        String reference = authorization.paymentId().toString();
        return faults.call(() -> translating(() -> translatePayment(client.payments(
                new PaymentRequest(amount(authorization.amount()), reference, MERCHANT_ACCOUNT), reference))));
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return faults.call(() -> translating(() -> translateModification(
                client.captures(gatewayReference, amount(amount)))));
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return faults.call(() -> translating(() -> translateModification(
                client.refunds(gatewayReference, amount(amount)))));
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return faults.call(() -> translating(() -> translateModification(client.cancels(gatewayReference))));
    }

    static GatewayResponse translatePayment(PaymentResponse response) {
        String resultCode = String.valueOf(response.resultCode());
        return switch (resultCode) {
            case AdyenClient.AUTHORISED -> GatewayResponse.approved(response.pspReference(), Duration.ZERO);
            case AdyenClient.REFUSED -> GatewayResponse.declined(
                    response.refusalReason() != null ? response.refusalReason() : AdyenClient.REFUSED, Duration.ZERO);
            case AdyenClient.ERROR -> GatewayResponse.error(
                    response.refusalReason() != null ? response.refusalReason() : AdyenClient.ERROR, Duration.ZERO);
            default -> GatewayResponse.error("adyen_unexpected_result:" + resultCode, Duration.ZERO);
        };
    }

    static GatewayResponse translateModification(ModificationResponse response) {
        return AdyenClient.RECEIVED.equals(response.status())
                ? GatewayResponse.approved(response.pspReference(), Duration.ZERO)
                : GatewayResponse.error("adyen_unexpected_status:" + response.status(), Duration.ZERO);
    }

    static GatewayResponse translateError(AdyenServiceException e) {
        int status = e.status();
        if (status >= 400 && status < 500 && status != 429) {
            return GatewayResponse.declined(e.errorCode(), Duration.ZERO);
        }
        return GatewayResponse.error(e.errorCode() != null ? e.errorCode() : "HTTP_" + status, Duration.ZERO);
    }

    private static GatewayResponse translating(Supplier<GatewayResponse> call) {
        try {
            return call.get();
        } catch (AdyenServiceException e) {
            return translateError(e);
        }
    }

    private static Amount amount(Money money) {
        return new Amount(money.currency().name(), money.amountMinor());
    }
}
