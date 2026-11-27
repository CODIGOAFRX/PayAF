package dev.pedrogomez.payaf.adapter.in.webhook;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import java.util.List;

/**
 * Verifica y traduce el webhook de una PSP concreta. Es la capa anticorrupción de entrada: cada
 * PSP firma y escribe a su manera, y aquí se convierte a la {@link GatewayNotification} de la
 * aplicación. La aplicación no ve nunca un webhook sin verificar.
 */
public interface WebhookParser {

    /** Identificador de la pasarela, que es el último tramo de {@code /webhooks/{gateway}}. */
    String gateway();

    /**
     * @return las notificaciones que trae (Adyen puede mandar varias; vacía si son eventos que
     *         PayAF no usa y basta con acusar recibo)
     * @throws InvalidSignatureException si la firma no es válida o falta
     * @throws MalformedWebhookException si la firma es buena pero el contenido no se entiende
     */
    List<GatewayNotification> parse(WebhookRequest request);
}
