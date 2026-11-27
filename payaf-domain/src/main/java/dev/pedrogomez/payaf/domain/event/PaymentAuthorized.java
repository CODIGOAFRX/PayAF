package dev.pedrogomez.payaf.domain.event;

import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Objects;

/** Una pasarela ha autorizado el pago. */
public record PaymentAuthorized(PaymentId paymentId, GatewayId gateway, String gatewayReference, Instant occurredAt)
        implements PaymentEvent {

    public PaymentAuthorized {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(gatewayReference);
        Objects.requireNonNull(occurredAt);
    }
}
