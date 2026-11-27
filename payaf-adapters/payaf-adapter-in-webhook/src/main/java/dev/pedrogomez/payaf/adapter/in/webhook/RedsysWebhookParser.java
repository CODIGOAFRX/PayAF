package dev.pedrogomez.payaf.adapter.in.webhook;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notificación online de Redsys: formulario con {@code Ds_SignatureVersion},
 * {@code Ds_MerchantParameters} (JSON en Base64) y {@code Ds_Signature}.
 * <p>
 * Firma: {@code Ds_Signature} es el HMAC-SHA256 de {@code Ds_MerchantParameters} en Base64
 * URL-safe (se acepta también el Base64 normal). Simplificación: el Redsys real deriva antes una
 * clave por pedido con 3DES.
 * <p>
 * Traducción, solo si {@code Ds_Response} es una aprobación ({@code 0000}-{@code 0099},
 * {@code 0400} o {@code 0900}): {@code Ds_TransactionType} {@code 2} → CAPTURE_CONFIRMED, {@code 3}
 * → REFUND_CONFIRMED (con referencia {@code Ds_Order-Ds_AuthorisationCode}, la misma que da el
 * adaptador de salida) y {@code 9} → AUTHORIZATION_REVERSED. Identificador:
 * {@code Ds_Order:Ds_TransactionType:Ds_AuthorisationCode}.
 */
public final class RedsysWebhookParser implements WebhookParser {

    private static final GatewayId REDSYS = GatewayId.of("redsys");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private final String secret;

    public RedsysWebhookParser(String secret) {
        this.secret = Objects.requireNonNull(secret);
    }

    @Override
    public String gateway() {
        return REDSYS.value();
    }

    @Override
    public List<GatewayNotification> parse(WebhookRequest request) {
        Map<String, String> form = form(request.bodyAsString());
        String parameters = form.get("Ds_MerchantParameters");
        String signature = form.get("Ds_Signature");
        if (parameters == null || signature == null) {
            throw new InvalidSignatureException("Faltan Ds_MerchantParameters o Ds_Signature");
        }
        byte[] received;
        try {
            received = Base64.getUrlDecoder().decode(signature.replace('+', '-').replace('/', '_'));
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("Ds_Signature no es Base64");
        }
        if (!Hmac.matches(Hmac.sha256(secret, parameters), received)) {
            throw new InvalidSignatureException("La firma de Redsys no coincide");
        }
        try {
            JsonNode p = JSON.readTree(new String(Base64.getDecoder().decode(parameters), StandardCharsets.UTF_8));
            String order = p.path("Ds_Order").asString("");
            String type = p.path("Ds_TransactionType").asString("");
            String authCode = p.path("Ds_AuthorisationCode").asString("").trim();
            int response = Integer.parseInt(p.path("Ds_Response").asString("9999"));
            if (!(response >= 0 && response <= 99) && response != 400 && response != 900) {
                return List.of();
            }
            String id = order + ":" + type + ":" + authCode;
            Instant at = when(p);
            return switch (type) {
                case "2" -> List.of(new GatewayNotification(REDSYS, id, order, NotificationType.CAPTURE_CONFIRMED,
                        Optional.empty(), Optional.empty(), at));
                case "9" -> List.of(new GatewayNotification(REDSYS, id, order,
                        NotificationType.AUTHORIZATION_REVERSED, Optional.empty(), Optional.empty(), at));
                case "3" -> List.of(new GatewayNotification(REDSYS, id, order, NotificationType.REFUND_CONFIRMED,
                        Optional.of(Money.of(Long.parseLong(p.path("Ds_Amount").asString("")),
                                currency(p.path("Ds_Currency").asString("")))),
                        Optional.of(order + "-" + authCode), at));
                default -> List.of();
            };
        } catch (RuntimeException e) {
            throw new MalformedWebhookException("Notificación de Redsys no válida: " + e.getMessage(), e);
        }
    }

    private static Instant when(JsonNode p) {
        try {
            LocalDate date = LocalDate.parse(p.path("Ds_Date").asString(""), DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            LocalTime time = LocalTime.parse(p.path("Ds_Hour").asString(""), DateTimeFormatter.ofPattern("HH:mm"));
            return date.atTime(time).atZone(MADRID).toInstant();
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    /** Código numérico ISO 4217, que es como manda Redsys la divisa. Duplicado del adaptador de salida, a propósito. */
    private static Currency currency(String numeric) {
        return switch (numeric) {
            case "978" -> Currency.EUR;
            case "840" -> Currency.USD;
            case "826" -> Currency.GBP;
            case "392" -> Currency.JPY;
            default -> throw new IllegalArgumentException("Divisa de Redsys desconocida: " + numeric);
        };
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }
}
