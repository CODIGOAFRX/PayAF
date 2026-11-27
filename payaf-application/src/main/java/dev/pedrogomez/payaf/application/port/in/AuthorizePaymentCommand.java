package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import java.util.Objects;

/**
 * Orden de autorizar un pago. Envuelve la {@link PaymentRequest} del dominio, que ya llega
 * validada: el adaptador de entrada traduce su formato (JSON, argumentos de la CLI) a los
 * objetos de valor del dominio, y si algo no es válido falla al construirlos.
 */
public record AuthorizePaymentCommand(PaymentRequest request) {

    public AuthorizePaymentCommand {
        Objects.requireNonNull(request, "la orden de pago no puede ser nula");
    }
}
