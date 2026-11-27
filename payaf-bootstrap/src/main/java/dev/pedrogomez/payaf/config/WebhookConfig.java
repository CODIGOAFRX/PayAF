package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.webhook.AdyenWebhookParser;
import dev.pedrogomez.payaf.adapter.in.webhook.RedsysWebhookParser;
import dev.pedrogomez.payaf.adapter.in.webhook.StripeWebhookParser;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Los verificadores de webhooks de cada PSP, con sus secretos. El {@code WebhookController} los
 * recibe todos y elige por el {@code {gateway}} de la URL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

    @Bean
    StripeWebhookParser stripeWebhookParser(WebhookProperties properties, Clock clock) {
        return new StripeWebhookParser(properties.secrets().stripe(), clock);
    }

    @Bean
    AdyenWebhookParser adyenWebhookParser(WebhookProperties properties) {
        return new AdyenWebhookParser(properties.secrets().adyen());
    }

    @Bean
    RedsysWebhookParser redsysWebhookParser(WebhookProperties properties) {
        return new RedsysWebhookParser(properties.secrets().redsys());
    }
}
