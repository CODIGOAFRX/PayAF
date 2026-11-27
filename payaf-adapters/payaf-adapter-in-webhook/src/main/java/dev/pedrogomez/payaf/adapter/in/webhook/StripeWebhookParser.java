package dev.pedrogomez.payaf.adapter.in.webhook;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhooks de Stripe.
 * <p>
 * Firma: cabecera {@code Stripe-Signature: t=<segundos>,v1=<hex>[,v1=...]}, con
 * {@code v1 = HMAC-SHA256(secreto, t + "." + cuerpo)}. Vale si alguna {@code v1} coincide y si
 * {@code t} no se aleja más de {@link #DEFAULT_TOLERANCE} del reloj (como hace el SDK de Stripe):
 * así una notificación interceptada no se puede reenviar más tarde (ataque de repetición).
 * <p>
 * Traducción: {@code payment_intent.succeeded} → CAPTURE_CONFIRMED; {@code refund.created} o
 * {@code refund.updated} con {@code status = succeeded} → REFUND_CONFIRMED;
 * {@code payment_intent.canceled} → AUTHORIZATION_REVERSED. El resto de eventos se ignoran.
 */
public final class StripeWebhookParser implements WebhookParser {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);
    static final String HEADER = "Stripe-Signature";

    private static final GatewayId STRIPE = GatewayId.of("stripe");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String secret;
    private final Clock clock;
    private final Duration tolerance;

    public StripeWebhookParser(String secret, Clock clock) {
        this(secret, clock, DEFAULT_TOLERANCE);
    }

    public StripeWebhookParser(String secret, Clock clock, Duration tolerance) {
        this.secret = Objects.requireNonNull(secret);
        this.clock = Objects.requireNonNull(clock);
        this.tolerance = Objects.requireNonNull(tolerance);
    }

    @Override
    public String gateway() {
        return STRIPE.value();
    }

    @Override
    public List<GatewayNotification> parse(WebhookRequest request) {
        verify(request);
        try {
            JsonNode event = JSON.readTree(request.bodyAsString());
            String id = event.path("id").asString("");
            JsonNode object = event.path("data").path("object");
            Instant at = Instant.ofEpochSecond(event.path("created").asLong());
            String type = event.path("type").asString("");
            return switch (type) {
                case "payment_intent.succeeded" -> List.of(new GatewayNotification(STRIPE, id,
                        object.path("id").asString(""), NotificationType.CAPTURE_CONFIRMED, Optional.empty(),
                        Optional.empty(), at));
                case "payment_intent.canceled" -> List.of(new GatewayNotification(STRIPE, id,
                        object.path("id").asString(""), NotificationType.AUTHORIZATION_REVERSED, Optional.empty(),
                        Optional.empty(), at));
                case "refund.created", "refund.updated" -> "succeeded".equals(object.path("status").asString(""))
                        ? List.of(new GatewayNotification(STRIPE, id, object.path("payment_intent").asString(""),
                        NotificationType.REFUND_CONFIRMED,
                        Optional.of(Money.of(object.path("amount").asLong(), Currency.valueOf(
                                object.path("currency").asString("").toUpperCase(Locale.ROOT)))),
                        Optional.of(object.path("id").asString("")), at))
                        : List.of();
                default -> List.of();
            };
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MalformedWebhookException("Evento de Stripe ilegible: " + e.getMessage(), e);
        }
    }

    private void verify(WebhookRequest request) {
        String header = request.header(HEADER)
                .orElseThrow(() -> new InvalidSignatureException("Falta la cabecera " + HEADER));
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new InvalidSignatureException("Marca de tiempo de Stripe no válida");
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("La cabecera " + HEADER + " no trae t y v1");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("La firma de Stripe ha caducado (" + age.toSeconds() + " s)");
        }
        byte[] signedPayload = (timestamp + "." + request.bodyAsString()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = Hmac.sha256(secret, signedPayload);
        for (String signature : signatures) {
            byte[] received;
            try {
                received = HexFormat.of().parseHex(signature);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (Hmac.matches(expected, received)) {
                return;
            }
        }
        throw new InvalidSignatureException("La firma de Stripe no coincide");
    }
}
