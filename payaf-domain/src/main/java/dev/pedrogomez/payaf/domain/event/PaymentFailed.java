package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.time.Instant;
import java.util.Objects;

/** El pago no se ha podido autorizar y queda en FAILED. */
public record PaymentFailed(PaymentId paymentId, String reason, Instant occurredAt) implements PaymentEvent {

    public PaymentFailed {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(occurredAt);
    }
}
