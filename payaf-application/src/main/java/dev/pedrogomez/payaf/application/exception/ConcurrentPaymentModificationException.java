package dev.pedrogomez.payaf.application.exception;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.util.Objects;

/**
 * Otra petición ha guardado el mismo pago entre que esta lo cargó y lo intentó guardar.
 * <p>
 * La lanza el adaptador de {@code PaymentRepository} al detectar, con control optimista de
 * versión, que el pago guardado ya no es el que se cargó. Sin ella, la segunda escritura
 * pisaría a la primera (dos capturas, o un reembolso perdido). Es de la aplicación, y no del
 * dominio, porque es un problema de concurrencia entre peticiones y no una regla del pago.
 * Código estable: {@value ApplicationException#CONCURRENT_MODIFICATION}.
 */
public final class ConcurrentPaymentModificationException extends ApplicationException {

    private final PaymentId paymentId;

    public ConcurrentPaymentModificationException(PaymentId paymentId) {
        super(CONCURRENT_MODIFICATION, "El pago " + paymentId + " ha cambiado mientras se procesaba; reinténtalo");
        this.paymentId = Objects.requireNonNull(paymentId);
    }

    public PaymentId paymentId() {
        return paymentId;
    }
}
