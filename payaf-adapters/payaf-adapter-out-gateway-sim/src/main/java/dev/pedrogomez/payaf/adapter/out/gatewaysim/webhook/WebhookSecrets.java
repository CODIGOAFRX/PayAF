package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

import java.util.Objects;

/**
 * Secretos con los que cada PSP simulada firma sus webhooks. Son los mismos que tiene configurados
 * el adaptador de webhooks para verificarlos: en la vida real, el secreto lo genera la PSP y el
 * comercio lo copia en su configuración.
 */
public record WebhookSecrets(String stripe, String adyen, String redsys) {

    public WebhookSecrets {
        Objects.requireNonNull(stripe);
        Objects.requireNonNull(adyen);
        Objects.requireNonNull(redsys);
    }
}
