package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;

/**
 * Evento del ciclo de vida de un pago. Lo emite el agregado
 * {@link dev.pedrogomez.payaf.domain.payment.Payment} y siempre identifica el pago afectado.
 */
public sealed interface PaymentEvent extends DomainEvent
        permits PaymentCreated, GatewayAttemptRecorded, PaymentAuthorized, PaymentFailed,
                PaymentCaptured, PaymentCanceled, PaymentRefunded {

    PaymentId paymentId();
}
