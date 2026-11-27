package dev.pedrogomez.payaf.adapter.in.webhook;

/** La firma del webhook falta, no cuadra o ha caducado: se contesta 401 y no llega a la aplicación. */
public final class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
