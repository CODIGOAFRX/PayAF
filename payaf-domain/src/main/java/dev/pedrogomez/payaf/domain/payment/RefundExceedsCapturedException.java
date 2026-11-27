package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.Money;

/**
 * El reembolso pedido, sumado a lo ya reembolsado, supera lo capturado.
 * <p>
 * Es dominio porque es una regla de dinero del negocio: no se puede devolver más de lo que
 * se cobró. Código estable: {@code "REFUND_EXCEEDS_CAPTURED"}.
 */
public final class RefundExceedsCapturedException extends DomainException {

    public static final String CODE = "REFUND_EXCEEDS_CAPTURED";

    private final PaymentId paymentId;
    private final Money requested;
    private final Money remainingRefundable;

    public RefundExceedsCapturedException(PaymentId paymentId, Money requested, Money remainingRefundable) {
        super(CODE, "El pago " + paymentId + " no admite un reembolso de " + requested
                + ": solo quedan " + remainingRefundable + " por reembolsar");
        this.paymentId = paymentId;
        this.requested = requested;
        this.remainingRefundable = remainingRefundable;
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public Money requested() {
        return requested;
    }

    public Money remainingRefundable() {
        return remainingRefundable;
    }
}
