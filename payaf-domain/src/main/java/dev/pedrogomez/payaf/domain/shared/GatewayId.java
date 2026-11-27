package dev.pedrogomez.payaf.domain.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identificador de una pasarela de pago (PSP), por ejemplo {@code stripe-eu}.
 * <p>
 * Es dominio porque la pasarela es un concepto central del enrutado: los pagos
 * recuerdan qué pasarela los autorizó, los circuit breakers van uno por pasarela y la
 * política de enrutado las ordena. Se exige un formato estricto ({@code [a-z0-9-]{2,32}},
 * siempre en minúsculas) para que sirva de clave estable en mapas, logs, métricas y URL
 * sin normalizaciones sorpresa: un valor en mayúsculas se rechaza, no se corrige.
 */
public record GatewayId(String value) implements Comparable<GatewayId> {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{2,32}");

    public GatewayId {
        Objects.requireNonNull(value, "el identificador de pasarela no puede ser nulo");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidValueException(
                    "Identificador de pasarela no válido: «" + value + "». Debe cumplir [a-z0-9-]{2,32}");
        }
    }

    public static GatewayId of(String value) {
        return new GatewayId(value);
    }

    @Override
    public int compareTo(GatewayId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
