package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.util.List;

/**
 * Puerto de entrada (driving): consultar pagos. Solo lee; devuelve vistas, nunca el agregado.
 */
public interface QueryPaymentsUseCase {

    /** @throws dev.pedrogomez.payaf.application.exception.ApplicationException PAYMENT_NOT_FOUND */
    PaymentView get(PaymentId paymentId);

    /**
     * Los últimos pagos, del más reciente al más antiguo.
     *
     * @param limit cuántos como mucho; al menos 1
     */
    List<PaymentView> recent(int limit);
}
