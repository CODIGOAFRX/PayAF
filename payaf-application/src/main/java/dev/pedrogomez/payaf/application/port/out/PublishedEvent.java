package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.GatewayHealthEvent;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentEvent;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Un evento de dominio en su forma publicada: el «idioma publicado» con el que PayAF cuenta hacia
 * fuera lo que pasa (al panel por SSE, a otros sistemas).
 * <p>
 * <strong>Por qué está en la aplicación.</strong> Qué se publica de cada evento (nombres, campos,
 * formato de importes y fechas) es un contrato con quien escucha y tiene que ser el mismo tanto si
 * sale por el outbox de PostgreSQL como por el de memoria. Si lo decidiera cada adaptador, el panel
 * vería cosas distintas según el perfil, y dos adaptadores no pueden compartir código. Aquí solo
 * se usan tipos del JDK (mapas, textos y números); pasarlo a JSON con Jackson es cosa de cada
 * adaptador. El dominio no se serializa nunca directamente: así, si se renombra un campo de un
 * record, el contrato no cambia sin querer.
 *
 * @param aggregateType {@value #PAYMENT} o {@value #GATEWAY}
 * @param aggregateId   el pago o la pasarela afectados
 * @param eventType     nombre estable del evento (el nombre simple del record, p. ej. {@code PaymentAuthorized})
 * @param payload       campos del evento: textos, números, booleanos, nulos y mapas anidados. No se
 *                      copia en profundidad: los mapas anidados que crea {@link #from} ya son inmutables
 * @param occurredAt    cuándo ocurrió
 */
public record PublishedEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload,
                             Instant occurredAt) {

    public static final String PAYMENT = "payment";
    public static final String GATEWAY = "gateway";

    public PublishedEvent {
        Objects.requireNonNull(aggregateType);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(eventType);
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(payload)));
        Objects.requireNonNull(occurredAt);
    }

    /** Traduce un evento de dominio a su forma publicada. */
    public static PublishedEvent from(DomainEvent event) {
        Objects.requireNonNull(event, "el evento no puede ser nulo");
        Map<String, Object> p = new LinkedHashMap<>();
        String type = event.getClass().getSimpleName();
        return switch (event) {
            case PaymentEvent pe -> {
                p.put("paymentId", pe.paymentId().toString());
                switch (pe) {
                    case PaymentCreated e -> {
                        p.put("amount", money(e.amount()));
                        p.put("country", e.country().value());
                        p.put("merchantReference", e.merchantReference());
                    }
                    case GatewayAttemptRecorded e -> p.put("attempt", attempt(e.attempt()));
                    case PaymentAuthorized e -> {
                        p.put("gateway", e.gateway().value());
                        p.put("gatewayReference", e.gatewayReference());
                    }
                    case PaymentFailed e -> p.put("reason", e.reason());
                    case PaymentCaptured e -> p.put("amount", money(e.amount()));
                    case PaymentCanceled e -> {
                        // Solo lleva el pago y el instante.
                    }
                    case PaymentRefunded e -> {
                        p.put("amount", money(e.amount()));
                        p.put("totalRefunded", money(e.totalRefunded()));
                        p.put("gatewayRefundReference", e.gatewayRefundReference());
                    }
                }
                p.put("occurredAt", pe.occurredAt().toString());
                yield new PublishedEvent(PAYMENT, pe.paymentId().toString(), type, p, pe.occurredAt());
            }
            case GatewayHealthEvent ge -> {
                p.put("gateway", ge.gateway().value());
                p.put("state", switch (ge) {
                    case CircuitOpened e -> "OPEN";
                    case CircuitHalfOpened e -> "HALF_OPEN";
                    case CircuitClosed e -> "CLOSED";
                });
                p.put("occurredAt", ge.occurredAt().toString());
                yield new PublishedEvent(GATEWAY, ge.gateway().value(), type, p, ge.occurredAt());
            }
        };
    }

    /** Un importe como {@code {"amount": "12.34", "currency": "EUR"}}: el decimal va en texto, sin redondeos. */
    public static Map<String, Object> money(Money money) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", money.toDecimalString());
        m.put("currency", money.currency().name());
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> attempt(GatewayAttempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gateway", a.gateway().value());
        m.put("outcome", a.outcome().name());
        m.put("latencyMs", a.latency().toMillis());
        m.put("code", a.code().orElse(null));
        m.put("gatewayReference", a.gatewayReference().orElse(null));
        m.put("at", a.at().toString());
        return Collections.unmodifiableMap(m);
    }
}
