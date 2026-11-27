package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Fila de {@code payment_refunds}: un reembolso aplicado. La restricción única
 * {@code (payment_id, gateway_refund_reference)} es la red de seguridad de la base de datos para
 * la regla del dominio de no contar dos veces el mismo reembolso. Solo se añaden; no cambian.
 */
@Entity
@Table(name = "payment_refunds")
public class PaymentRefundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private PaymentEntity payment;

    @Column(name = "gateway_refund_reference", nullable = false, updatable = false)
    private String gatewayRefundReference;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "refunded_at", nullable = false, updatable = false)
    private Instant refundedAt;

    /** Para JPA. */
    protected PaymentRefundEntity() {
    }

    PaymentRefundEntity(PaymentEntity payment, String gatewayRefundReference, long amountMinor, Instant refundedAt) {
        this.payment = payment;
        this.gatewayRefundReference = gatewayRefundReference;
        this.amountMinor = amountMinor;
        this.refundedAt = refundedAt;
    }

    String getGatewayRefundReference() {
        return gatewayRefundReference;
    }

    long getAmountMinor() {
        return amountMinor;
    }

    Instant getRefundedAt() {
        return refundedAt;
    }
}
