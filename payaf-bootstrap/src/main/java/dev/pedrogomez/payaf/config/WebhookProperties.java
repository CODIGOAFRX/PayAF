package dev.pedrogomez.payaf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secretos de los webhooks de cada PSP ({@code payaf.webhooks.secrets.*}). Los usan los dos lados:
 * el adaptador de webhooks para verificar y el simulador para firmar.
 */
@ConfigurationProperties(prefix = "payaf.webhooks")
public record WebhookProperties(Secrets secrets) {

    public record Secrets(String stripe, String adyen, String redsys) {
    }
}
