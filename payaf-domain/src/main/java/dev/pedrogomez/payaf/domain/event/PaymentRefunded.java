package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * Se ha reembolsado una parte o la totalidad de lo capturado.
 *
 * @param amount        lo reembolsado en esta operación
 * @param totalRefunded lo reembolsado en total, contando esta operación
 */
public record PaymentRefunded(PaymentId paymentId, Money amount, Money totalRefunded, Instant occurredAt)
        implements PaymentEvent {

    public PaymentRefunded {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(totalRefunded);
        Objects.requireNonNull(occurredAt);
    }
}
