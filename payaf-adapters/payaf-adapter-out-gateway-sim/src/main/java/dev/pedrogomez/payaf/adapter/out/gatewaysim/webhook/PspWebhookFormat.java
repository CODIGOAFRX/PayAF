package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

import dev.pedrogomez.payaf.domain.shared.Money;

/**
 * Cómo escribe y firma sus webhooks una PSP simulada. Cada PSP tiene el suyo, en su paquete, con
 * su formato nativo (ver {@code StripeWebhooks}, {@code AdyenWebhooks} y {@code RedsysWebhooks}).
 */
public interface PspWebhookFormat {

    /**
     * La PSP confirma una captura.
     *
     * @param paymentReference   referencia de la autorización (la que guarda el pago)
     * @param operationReference referencia de la captura en la PSP (en Adyen es distinta de la del pago)
     */
    SimulatedWebhook captured(String paymentReference, String operationReference, Money amount);

    /**
     * La PSP confirma un reembolso.
     *
     * @param refundReference la referencia del reembolso que devolvió la PSP al hacerlo
     */
    SimulatedWebhook refunded(String paymentReference, String refundReference, Money amount);
}
