package dev.pedrogomez.payaf.domain.shared;

/**
 * Un valor no cumple las reglas de su tipo (un país con tres letras, un importe con
 * demasiados decimales, una clave de idempotencia vacía...).
 * <p>
 * Es dominio porque las reglas de validez de cada valor son conocimiento del negocio:
 * el adaptador REST puede validar el formato del JSON, pero quien sabe que el yen no
 * tiene céntimos es {@link Currency}. Código estable: {@code "INVALID_VALUE"}.
 */
public final class InvalidValueException extends DomainException {

    public static final String CODE = "INVALID_VALUE";

    public InvalidValueException(String message) {
        super(CODE, message);
    }
}
