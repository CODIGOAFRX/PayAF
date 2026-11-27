package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.time.Instant;
import java.util.Objects;

/** Se ha anulado la autorización del pago antes de capturarlo. */
public record PaymentCanceled(PaymentId paymentId, Instant occurredAt) implements PaymentEvent {

    public PaymentCanceled {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(occurredAt);
    }
}
