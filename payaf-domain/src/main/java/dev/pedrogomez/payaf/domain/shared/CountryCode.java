package dev.pedrogomez.payaf.domain.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * País en formato ISO 3166-1 alfa-2 ({@code ES}, {@code US}, {@code GB}...).
 * <p>
 * Es dominio porque el país del pago decide qué pasarelas pueden procesarlo: es una
 * entrada de la política de enrutado. Usar un tipo propio en lugar de un {@code String}
 * impide confundirlo con cualquier otro texto y garantiza que siempre está bien formado.
 * Solo se valida la forma (dos letras mayúsculas); no se comprueba contra la lista
 * oficial de países asignados.
 */
public record CountryCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}");

    public CountryCode {
        Objects.requireNonNull(value, "el código de país no puede ser nulo");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidValueException(
                    "Código de país no válido: «" + value + "». Debe ser ISO 3166-1 alfa-2 en mayúsculas");
        }
    }

    public static CountryCode of(String value) {
        return new CountryCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
