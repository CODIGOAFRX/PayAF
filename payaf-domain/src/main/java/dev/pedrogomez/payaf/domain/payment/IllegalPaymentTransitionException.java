package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.DomainException;
import java.util.Objects;

/**
 * Se ha pedido al pago algo que su estado actual no permite (capturar un pago pendiente,
 * reembolsar uno fallido, registrar un intento en uno ya autorizado...).
 * <p>
 * Es dominio porque es la máquina de estados de {@link PaymentStatus} la que lo prohíbe.
 * Lleva el estado de origen y el de destino para que el error se explique solo. En
 * {@link Payment#recordAttempt} el estado no cambia, así que el destino es el propio estado
 * actual: lo que se rechaza es añadir un intento a un pago que ya no está PENDING.
 * Código estable: {@code "ILLEGAL_TRANSITION"}.
 */
public final class IllegalPaymentTransitionException extends DomainException {

    public static final String CODE = "ILLEGAL_TRANSITION";

    private final PaymentId paymentId;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentTransitionException(PaymentId paymentId, PaymentStatus from, PaymentStatus to) {
        super(CODE, "El pago " + paymentId + " no puede pasar de " + from + " a " + to);
        this.paymentId = Objects.requireNonNull(paymentId);
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
