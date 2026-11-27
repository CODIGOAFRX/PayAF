package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.Money;

/**
 * Puerto de entrada (driving): devolver parte o todo lo capturado, en la pasarela que
 * autorizó el pago.
 */
public interface RefundPaymentUseCase {

    /**
     * @throws dev.pedrogomez.payaf.application.exception.ApplicationException PAYMENT_NOT_FOUND, o
     *         GATEWAY_OPERATION_FAILED si la PSP no aprueba el reembolso (el pago no cambia)
     * @throws dev.pedrogomez.payaf.domain.shared.DomainException si el pago no admite ese reembolso
     *         (no capturado, importe excesivo, otra divisa...)
     */
    PaymentView refund(PaymentId paymentId, Money amount);
}
