package dev.pedrogomez.payaf.adapter.in.webhook;

/** Webhook bien firmado pero con un contenido que no se entiende: se contesta 400. */
public final class MalformedWebhookException extends RuntimeException {

    public MalformedWebhookException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedWebhookException(String message) {
        super(message);
    }
}
