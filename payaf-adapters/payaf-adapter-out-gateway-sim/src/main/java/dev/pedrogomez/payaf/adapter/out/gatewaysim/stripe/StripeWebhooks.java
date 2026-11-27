package dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures.quote;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.References;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspWebhookFormat;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.SimulatedWebhook;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Webhooks de Stripe: un objeto {@code event} en JSON y la cabecera
 * {@code Stripe-Signature: t=<segundos>,v1=<hex>}, donde {@code v1} es
 * {@code HMAC-SHA256(secreto, t + "." + cuerpo)}. Stripe firma el instante para que una
 * notificación capturada no se pueda reenviar días después (el receptor tiene una tolerancia).
 * <ul>
 *   <li>Captura: {@code payment_intent.succeeded} con el PaymentIntent en {@code data.object}.</li>
 *   <li>Reembolso: {@code refund.created} con el {@code refund} ({@code re_...}) y su {@code payment_intent}.</li>
 * </ul>
 */
public final class StripeWebhooks implements PspWebhookFormat {

    public static final String SIGNATURE_HEADER = "Stripe-Signature";

    private final String secret;
    private final Clock clock;
    private final Random random;

    public StripeWebhooks(String secret, Clock clock, Random random) {
        this.secret = Objects.requireNonNull(secret);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public SimulatedWebhook captured(String paymentReference, String operationReference, Money amount) {
        String object = "{\"id\":" + quote(paymentReference) + ",\"object\":\"payment_intent\",\"amount\":"
                + amount.amountMinor() + ",\"amount_received\":" + amount.amountMinor() + ",\"currency\":"
                + quote(currency(amount)) + ",\"status\":\"succeeded\"}";
        return event("payment_intent.succeeded", object);
    }

    @Override
    public SimulatedWebhook refunded(String paymentReference, String refundReference, Money amount) {
        String object = "{\"id\":" + quote(refundReference) + ",\"object\":\"refund\",\"payment_intent\":"
                + quote(paymentReference) + ",\"amount\":" + amount.amountMinor() + ",\"currency\":"
                + quote(currency(amount)) + ",\"status\":\"succeeded\"}";
        return event("refund.created", object);
    }

    private SimulatedWebhook event(String type, String object) {
        long created = clock.instant().getEpochSecond();
        String body = "{\"id\":" + quote("evt_" + References.alphanumeric(random, 24)) + ",\"object\":\"event\","
                + "\"type\":" + quote(type) + ",\"created\":" + created + ",\"livemode\":false,"
                + "\"data\":{\"object\":" + object + "}}";
        return new SimulatedWebhook("stripe", "application/json",
                Map.of(SIGNATURE_HEADER, signatureHeader(secret, created, body)), body);
    }

    /** El valor de {@code Stripe-Signature} para ese cuerpo e instante. */
    public static String signatureHeader(String secret, long timestamp, String body) {
        String v1 = HexFormat.of().formatHex(PspSignatures.hmacSha256(secret, timestamp + "." + body));
        return "t=" + timestamp + ",v1=" + v1;
    }

    private static String currency(Money amount) {
        return amount.currency().name().toLowerCase(Locale.ROOT);
    }
}
