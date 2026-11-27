package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.PaymentId;

/**
 * Puerto de entrada (driving): anular la autorización de un pago antes de capturarlo, en
 * la pasarela que lo autorizó.
 */
public interface CancelPaymentUseCase {

    /**
     * @throws dev.pedrogomez.payaf.application.exception.ApplicationException PAYMENT_NOT_FOUND, o
     *         GATEWAY_OPERATION_FAILED si la PSP no aprueba la anulación (el pago no cambia)
     * @throws dev.pedrogomez.payaf.domain.shared.DomainException si el estado del pago no admite anularlo
     */
    PaymentView cancel(PaymentId paymentId);
}
