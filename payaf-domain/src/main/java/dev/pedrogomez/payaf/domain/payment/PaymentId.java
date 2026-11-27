package dev.pedrogomez.payaf.domain.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidad de un pago.
 * <p>
 * Es dominio porque el pago es una entidad: dos pagos con el mismo importe y país siguen
 * siendo pagos distintos, y lo que los distingue es esta identidad. Envolver el
 * {@link UUID} evita confundirlo con otros UUID del sistema. El dominio no genera
 * identificadores por su cuenta: los recibe de fuera (de un puerto de la aplicación), y
 * así los tests pueden usar valores fijos.
 */
public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "el identificador de pago no puede ser nulo");
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }

    /** @throws IllegalArgumentException si el texto no es un UUID */
    public static PaymentId of(String value) {
        return new PaymentId(UUID.fromString(Objects.requireNonNull(value, "el identificador no puede ser nulo")));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
