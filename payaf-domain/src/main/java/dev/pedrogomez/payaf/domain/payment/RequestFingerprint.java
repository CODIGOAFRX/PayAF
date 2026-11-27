package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Huella SHA-256 (64 caracteres hexadecimales en minúsculas) del contenido de una orden de pago.
 * <p>
 * Es dominio porque responde a una pregunta de negocio: si llega otra vez la misma clave
 * de idempotencia, ¿es la misma orden o el comercio está reutilizando la clave para otra
 * cosa? Si la huella coincide, se devuelve el pago ya creado; si no, hay un conflicto.
 * Guardar la huella en lugar de la orden entera permite compararlas sin depender de cómo
 * se persista la orden.
 */
public record RequestFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public RequestFingerprint {
        Objects.requireNonNull(value, "la huella no puede ser nula");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidValueException("Huella no válida: debe ser SHA-256 en hexadecimal y en minúsculas");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
