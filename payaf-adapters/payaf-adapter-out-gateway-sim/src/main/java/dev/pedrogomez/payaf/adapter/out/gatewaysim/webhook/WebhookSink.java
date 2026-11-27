package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

/**
 * Adónde deja el simulador los webhooks que emite. Lo implementa bootstrap, que los envía por HTTP
 * a la propia aplicación con un pequeño retraso, como haría una PSP de verdad. {@link #enqueue} no
 * debe bloquear: se llama justo después de responder a una captura o un reembolso.
 */
@FunctionalInterface
public interface WebhookSink {

    void enqueue(SimulatedWebhook webhook);

    /** Un destino que los descarta: el simulador no emite webhooks. */
    static WebhookSink none() {
        return webhook -> {
        };
    }
}
