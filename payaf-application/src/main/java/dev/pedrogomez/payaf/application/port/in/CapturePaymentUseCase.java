package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.PaymentId;

/**
 * Puerto de entrada (driving): cobrar el total de un pago autorizado.
 * <p>
 * Se pide a la misma pasarela que lo autorizó, sin failover: la autorización (el dinero
 * reservado) solo existe en esa PSP.
 */
public interface CapturePaymentUseCase {

    /**
     * @throws dev.pedrogomez.payaf.application.exception.ApplicationException PAYMENT_NOT_FOUND, o
     *         GATEWAY_OPERATION_FAILED si la PSP no aprueba la captura (el pago no cambia)
     * @throws dev.pedrogomez.payaf.domain.shared.DomainException si el estado del pago no admite capturarlo
     */
    PaymentView capture(PaymentId paymentId);
}
