package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;

/** Se ha capturado (cobrado) el total autorizado del pago. */
public record PaymentCaptured(PaymentId paymentId, Money amount, Instant occurredAt) implements PaymentEvent {

    public PaymentCaptured {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(occurredAt);
    }
}
