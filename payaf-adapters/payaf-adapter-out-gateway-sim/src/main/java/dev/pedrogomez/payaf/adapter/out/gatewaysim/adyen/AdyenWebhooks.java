package dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures.quote;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspWebhookFormat;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.SimulatedWebhook;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Notificaciones estándar de Adyen: {@code {"live": "false", "notificationItems": [...]}} con un
 * {@code NotificationRequestItem} por operación. La firma no va en una cabecera sino dentro de
 * cada elemento, en {@code additionalData.hmacSignature}: el HMAC-SHA256 en Base64 de
 * {@code pspReference:originalReference:merchantAccountCode:merchantReference:value:currency:eventCode:success}.
 * <ul>
 *   <li>Captura: {@code eventCode = CAPTURE}, {@code pspReference} de la captura y
 *       {@code originalReference} el de la autorización.</li>
 *   <li>Reembolso: {@code eventCode = REFUND}, {@code pspReference} del reembolso.</li>
 * </ul>
 * El {@code merchantReference} va vacío: el simulador no lo conserva entre la autorización y la
 * modificación (la Adyen real sí lo repetiría).
 */
public final class AdyenWebhooks implements PspWebhookFormat {

    private final String secret;
    private final Clock clock;

    public AdyenWebhooks(String secret, Clock clock) {
        this.secret = Objects.requireNonNull(secret);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SimulatedWebhook captured(String paymentReference, String operationReference, Money amount) {
        return item("CAPTURE", operationReference, paymentReference, amount);
    }

    @Override
    public SimulatedWebhook refunded(String paymentReference, String refundReference, Money amount) {
        return item("REFUND", refundReference, paymentReference, amount);
    }

    private SimulatedWebhook item(String eventCode, String pspReference, String originalReference, Money amount) {
        String merchantReference = "";
        String currency = amount.currency().name();
        String hmac = signature(secret, pspReference, originalReference, AdyenPaymentGateway.MERCHANT_ACCOUNT,
                merchantReference, amount.amountMinor(), currency, eventCode, "true");
        String body = "{\"live\":\"false\",\"notificationItems\":[{\"NotificationRequestItem\":{"
                + "\"additionalData\":{\"hmacSignature\":" + quote(hmac) + "},"
                + "\"amount\":{\"currency\":" + quote(currency) + ",\"value\":" + amount.amountMinor() + "},"
                + "\"eventCode\":" + quote(eventCode) + ","
                + "\"eventDate\":" + quote(clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()) + ","
                + "\"merchantAccountCode\":" + quote(AdyenPaymentGateway.MERCHANT_ACCOUNT) + ","
                + "\"merchantReference\":" + quote(merchantReference) + ","
                + "\"originalReference\":" + quote(originalReference) + ","
                + "\"pspReference\":" + quote(pspReference) + ","
                + "\"success\":\"true\"}}]}";
        return new SimulatedWebhook("adyen", "application/json", Map.of(), body);
    }

    /** El {@code hmacSignature} de un elemento con esos campos. */
    public static String signature(String secret, String pspReference, String originalReference,
                                   String merchantAccountCode, String merchantReference, long value, String currency,
                                   String eventCode, String success) {
        String signed = String.join(":", pspReference, originalReference, merchantAccountCode, merchantReference,
                Long.toString(value), currency, eventCode, success);
        return Base64.getEncoder().encodeToString(PspSignatures.hmacSha256(secret, signed));
    }
}
