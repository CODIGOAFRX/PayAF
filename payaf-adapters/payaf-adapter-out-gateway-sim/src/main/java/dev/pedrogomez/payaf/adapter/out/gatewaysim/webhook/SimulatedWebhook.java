package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

import java.util.Map;
import java.util.Objects;

/**
 * Una notificación asíncrona (webhook) tal como la mandaría la PSP: cabeceras y cuerpo ya
 * firmados en su formato nativo. Quien la envía por HTTP (bootstrap) no necesita entenderla.
 *
 * @param gateway     pasarela que la emite; es el último tramo de la URL ({@code /webhooks/{gateway}})
 * @param contentType tipo del cuerpo ({@code application/json} o {@code application/x-www-form-urlencoded})
 * @param headers     cabeceras propias de la PSP (p. ej. {@code Stripe-Signature})
 * @param body        cuerpo exacto que se firma y se envía
 */
public record SimulatedWebhook(String gateway, String contentType, Map<String, String> headers, String body) {

    public SimulatedWebhook {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(contentType);
        headers = Map.copyOf(Objects.requireNonNull(headers));
        Objects.requireNonNull(body);
    }
}
