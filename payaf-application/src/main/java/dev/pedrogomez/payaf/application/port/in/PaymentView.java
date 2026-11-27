package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.Refund;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Foto inmutable y de solo lectura de un pago, tal como sale por los puertos de entrada.
 * <p>
 * Los adaptadores de entrada (REST, CLI, panel) nunca reciben el agregado {@link Payment}:
 * si lo recibieran podrían llamar a {@code capture()} o {@code refund()} saltándose el caso
 * de uso (y con él la pasarela, la transacción y los eventos). Con esta vista solo pueden
 * leer. Reutiliza los objetos de valor del dominio, que ya son inmutables.
 *
 * @param failureReason motivo del fallo si el pago está FAILED (p. ej. {@code NO_GATEWAY_AVAILABLE})
 * @param refunds       reembolsos aplicados, con su referencia en la PSP
 */
public record PaymentView(
        PaymentId id,
        Money amount,
        CountryCode country,
        String merchantReference,
        PaymentStatus status,
        Optional<GatewayId> authorizedBy,
        Optional<String> gatewayReference,
        Optional<String> failureReason,
        Money refunded,
        Money remainingRefundable,
        List<Refund> refunds,
        List<GatewayAttempt> attempts,
        Instant createdAt,
        Instant updatedAt) {

    public PaymentView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(country);
        Objects.requireNonNull(merchantReference);
        Objects.requireNonNull(status);
        Objects.requireNonNull(authorizedBy);
        Objects.requireNonNull(gatewayReference);
        Objects.requireNonNull(failureReason);
        Objects.requireNonNull(refunded);
        Objects.requireNonNull(remainingRefundable);
        refunds = List.copyOf(Objects.requireNonNull(refunds));
        attempts = List.copyOf(Objects.requireNonNull(attempts));
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    public static PaymentView from(Payment payment) {
        Objects.requireNonNull(payment, "el pago no puede ser nulo");
        return new PaymentView(
                payment.id(),
                payment.amount(),
                payment.request().country(),
                payment.request().merchantReference(),
                payment.status(),
                payment.authorizedBy(),
                payment.gatewayReference(),
                payment.failureReason(),
                payment.refundedAmount(),
                payment.remainingRefundable(),
                payment.refunds(),
                payment.attempts(),
                payment.createdAt(),
                payment.updatedAt());
    }
}
