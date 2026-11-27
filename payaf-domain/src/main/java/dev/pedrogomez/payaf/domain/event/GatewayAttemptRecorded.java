package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.time.Instant;
import java.util.Objects;

/**
 * Se ha registrado en el pago un intento de autorización en una pasarela. Lleva el intento
 * completo para que el panel pueda contar el failover paso a paso.
 */
public record GatewayAttemptRecorded(PaymentId paymentId, GatewayAttempt attempt, Instant occurredAt)
        implements PaymentEvent {

    public GatewayAttemptRecorded {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(occurredAt);
    }
}
