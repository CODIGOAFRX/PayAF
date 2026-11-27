package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.util.Objects;

/**
 * Clave de idempotencia que envía el comercio con cada orden de pago.
 * <p>
 * Es dominio porque la idempotencia es una garantía de negocio: si el comercio repite
 * la misma orden (por un reintento de red, por ejemplo), no se le puede cobrar dos veces.
 * La clave identifica la intención del comercio. Se limita a 1-255 caracteres ASCII
 * imprimibles (0x20-0x7E), sin espacios en los extremos, para poder compararla carácter a
 * carácter sin ambigüedades de codificación ni espacios invisibles.
 */
public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 255;

    public IdempotencyKey {
        Objects.requireNonNull(value, "la clave de idempotencia no puede ser nula");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new InvalidValueException("La clave de idempotencia debe tener entre 1 y 255 caracteres");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new InvalidValueException("La clave de idempotencia solo admite ASCII imprimible");
            }
        }
        if (value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') {
            throw new InvalidValueException("La clave de idempotencia no puede empezar ni acabar con espacios");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
