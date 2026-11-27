package dev.pedrogomez.payaf.adapter.in.webhook;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notificaciones estándar de Adyen ({@code notificationItems}).
 * <p>
 * Firma: no va en una cabecera, sino en cada elemento ({@code additionalData.hmacSignature}): el
 * HMAC-SHA256 en Base64 de
 * {@code pspReference:originalReference:merchantAccountCode:merchantReference:value:currency:eventCode:success}.
 * Si un solo elemento no cuadra, se rechaza el lote entero.
 * <p>
 * Traducción (solo los elementos con {@code success = "true"}): {@code CAPTURE} →
 * CAPTURE_CONFIRMED, {@code REFUND} → REFUND_CONFIRMED (el {@code pspReference} es el del
 * reembolso), {@code CANCELLATION} → AUTHORIZATION_REVERSED. La referencia del pago es
 * {@code originalReference}, y el identificador de la notificación, {@code eventCode:pspReference}.
 */
public final class AdyenWebhookParser implements WebhookParser {

    private static final GatewayId ADYEN = GatewayId.of("adyen");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String secret;

    public AdyenWebhookParser(String secret) {
        this.secret = Objects.requireNonNull(secret);
    }

    @Override
    public String gateway() {
        return ADYEN.value();
    }

    @Override
    public List<GatewayNotification> parse(WebhookRequest request) {
        JsonNode root;
        try {
            root = JSON.readTree(request.bodyAsString());
        } catch (JacksonException e) {
            throw new InvalidSignatureException("Notificación de Adyen ilegible: no se puede comprobar su firma");
        }
        List<JsonNode> items = new ArrayList<>();
        root.path("notificationItems").valueStream().forEach(i -> items.add(i.path("NotificationRequestItem")));
        if (items.isEmpty()) {
            throw new InvalidSignatureException("La notificación de Adyen no trae elementos firmados");
        }
        items.forEach(this::verify);
        try {
            List<GatewayNotification> notifications = new ArrayList<>();
            for (JsonNode item : items) {
                if (!"true".equals(text(item, "success"))) {
                    continue;
                }
                String eventCode = text(item, "eventCode");
                String pspReference = text(item, "pspReference");
                String original = text(item, "originalReference");
                String id = eventCode + ":" + pspReference;
                Instant at = item.hasNonNull("eventDate")
                        ? OffsetDateTime.parse(text(item, "eventDate")).toInstant() : Instant.EPOCH;
                switch (eventCode) {
                    case "CAPTURE" -> notifications.add(new GatewayNotification(ADYEN, id, original,
                            NotificationType.CAPTURE_CONFIRMED, Optional.empty(), Optional.empty(), at));
                    case "CANCELLATION" -> notifications.add(new GatewayNotification(ADYEN, id, original,
                            NotificationType.AUTHORIZATION_REVERSED, Optional.empty(), Optional.empty(), at));
                    case "REFUND" -> notifications.add(new GatewayNotification(ADYEN, id, original,
                            NotificationType.REFUND_CONFIRMED,
                            Optional.of(Money.of(item.path("amount").path("value").asLong(),
                                    Currency.valueOf(item.path("amount").path("currency").asString("")))),
                            Optional.of(pspReference), at));
                    default -> {
                        // AUTHORISATION y demás: PayAF no los usa; basta con acusar recibo.
                    }
                }
            }
            return notifications;
        } catch (RuntimeException e) {
            throw new MalformedWebhookException("Notificación de Adyen no válida: " + e.getMessage(), e);
        }
    }

    private void verify(JsonNode item) {
        String signed = String.join(":", text(item, "pspReference"), text(item, "originalReference"),
                text(item, "merchantAccountCode"), text(item, "merchantReference"),
                item.path("amount").path("value").asString(""), item.path("amount").path("currency").asString(""),
                text(item, "eventCode"), text(item, "success"));
        byte[] received;
        try {
            received = Base64.getDecoder().decode(item.path("additionalData").path("hmacSignature").asString(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("hmacSignature de Adyen no es Base64");
        }
        if (received.length == 0 || !Hmac.matches(Hmac.sha256(secret, signed), received)) {
            throw new InvalidSignatureException("La firma de Adyen no coincide");
        }
    }

    private static String text(JsonNode item, String field) {
        return item.path(field).asString("");
    }
}
