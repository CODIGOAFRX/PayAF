package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.DomainException;
import java.time.Instant;

/**
 * Se ha pedido un cambio al pago con un instante anterior a su última actualización.
 * <p>
 * Es dominio porque la historia de un pago tiene que poder leerse en orden: los eventos y
 * los intentos son la auditoría del pago, y un cambio fechado antes que el anterior la
 * volvería incoherente. Además, un pago con {@code updatedAt} anterior a {@code createdAt}
 * ya no se podría rehidratar ({@link Payment#rehydrate} lo rechaza). Un instante igual al
 * último sí vale: dos cambios pueden ocurrir en el mismo instante del reloj.
 * Código estable: {@code "NON_MONOTONIC_TIME"}.
 */
public final class NonMonotonicTimeException extends DomainException {

    public static final String CODE = "NON_MONOTONIC_TIME";

    private final PaymentId paymentId;
    private final Instant lastUpdate;
    private final Instant received;

    public NonMonotonicTimeException(PaymentId paymentId, Instant lastUpdate, Instant received) {
        super(CODE, "El pago " + paymentId + " se actualizó por última vez en " + lastUpdate
                + " y no admite un cambio fechado en " + received);
        this.paymentId = paymentId;
        this.lastUpdate = lastUpdate;
        this.received = received;
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public Instant lastUpdate() {
        return lastUpdate;
    }

    public Instant received() {
        return received;
    }
}
