package dev.pedrogomez.payaf.adapter.in.rest;

/** El cuerpo de la petición falta o no tiene la forma esperada: 400 con {@code code = INVALID_BODY}. */
public final class InvalidBodyException extends RuntimeException {

    public InvalidBodyException(String message) {
        super(message);
    }
}
