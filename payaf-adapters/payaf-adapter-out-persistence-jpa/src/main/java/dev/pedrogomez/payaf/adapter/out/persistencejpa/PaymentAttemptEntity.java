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
 * Fila de {@code payment_attempts}: un intento de autorización de un pago. Los intentos solo se
 * añaden, nunca cambian, y {@code seq} guarda su orden. La latencia va en nanosegundos para que
 * el {@code Duration} del dominio vuelva tal cual.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private PaymentEntity payment;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(nullable = false, updatable = false, length = 64)
    private String gateway;

    @Column(nullable = false, updatable = false, length = 16)
    private String outcome;

    @Column(name = "latency_nanos", nullable = false, updatable = false)
    private long latencyNanos;

    @Column(updatable = false)
    private String code;

    @Column(name = "gateway_reference", updatable = false)
    private String gatewayReference;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    /** Para JPA. */
    protected PaymentAttemptEntity() {
    }

    PaymentAttemptEntity(PaymentEntity payment, int seq, String gateway, String outcome, long latencyNanos,
                         String code, String gatewayReference, Instant attemptedAt) {
        this.payment = payment;
        this.seq = seq;
        this.gateway = gateway;
        this.outcome = outcome;
        this.latencyNanos = latencyNanos;
        this.code = code;
        this.gatewayReference = gatewayReference;
        this.attemptedAt = attemptedAt;
    }

    int getSeq() {
        return seq;
    }

    String getGateway() {
        return gateway;
    }

    String getOutcome() {
        return outcome;
    }

    long getLatencyNanos() {
        return latencyNanos;
    }

    String getCode() {
        return code;
    }

    String getGatewayReference() {
        return gatewayReference;
    }

    Instant getAttemptedAt() {
        return attemptedAt;
    }
}
