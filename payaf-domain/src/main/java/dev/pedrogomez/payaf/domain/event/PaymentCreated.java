package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;

/** Se ha creado un pago nuevo, en estado PENDING. */
public record PaymentCreated(
        PaymentId paymentId, Money amount, CountryCode country, String merchantReference, Instant occurredAt)
        implements PaymentEvent {

    public PaymentCreated {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(country);
        Objects.requireNonNull(merchantReference);
        Objects.requireNonNull(occurredAt);
    }
}
