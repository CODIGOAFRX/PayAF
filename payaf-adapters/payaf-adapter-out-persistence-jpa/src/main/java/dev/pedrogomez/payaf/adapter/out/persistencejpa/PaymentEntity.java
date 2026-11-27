package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

/**
 * Fila de la tabla {@code payments}: la forma en que un pago se guarda, no el pago.
 * <p>
 * <strong>Por qué hay una entidad JPA aparte y no se anota el dominio.</strong> Es la lección
 * clave del hexágono. Si {@code Payment} llevara {@code @Entity}, el dominio dependería de JPA
 * y, sobre todo, tendría que plegarse a lo que JPA necesita: un constructor vacío, campos
 * mutables que Hibernate rellena por reflexión saltándose los invariantes, colecciones que son
 * proxies perezosos que fallan fuera de la sesión y una forma decidida por las tablas en lugar de
 * por el negocio. Con la entidad separada, el agregado sigue siendo Java puro, se construye
 * siempre por {@code Payment.create} o {@code Payment.rehydrate} (que valida todo lo que se carga)
 * y el esquema puede cambiar sin tocar el dominio. El precio es un mapeo explícito
 * ({@link PaymentEntityMapper}), y es un precio pequeño: es justo el código que dice cómo se
 * traduce el negocio a filas.
 * <p>
 * La entidad es tonta a propósito: tipos simples (textos, números, UUID, instantes), sin
 * reglas. {@link #version} es la versión optimista de JPA ({@code @Version}): Hibernate la
 * comprueba y la incrementa en cada {@code UPDATE}. Empieza en 0 al insertar, mientras que
 * {@code Payment.version()} usa 0 para «nunca guardado», así que la versión del dominio es
 * siempre esta más uno (ver {@link JpaPaymentRepository}).
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "merchant_reference", nullable = false)
    private String merchantReference;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "authorized_by", length = 64)
    private String authorizedBy;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq")
    @BatchSize(size = 50)
    private List<PaymentAttemptEntity> attempts = new ArrayList<>();

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id")
    @BatchSize(size = 50)
    private List<PaymentRefundEntity> refunds = new ArrayList<>();

    /** Para JPA. */
    protected PaymentEntity() {
    }

    PaymentEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    long getAmountMinor() {
        return amountMinor;
    }

    void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    String getCurrency() {
        return currency;
    }

    void setCurrency(String currency) {
        this.currency = currency;
    }

    String getCountry() {
        return country;
    }

    void setCountry(String country) {
        this.country = country;
    }

    String getMerchantReference() {
        return merchantReference;
    }

    void setMerchantReference(String merchantReference) {
        this.merchantReference = merchantReference;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getAuthorizedBy() {
        return authorizedBy;
    }

    void setAuthorizedBy(String authorizedBy) {
        this.authorizedBy = authorizedBy;
    }

    String getGatewayReference() {
        return gatewayReference;
    }

    void setGatewayReference(String gatewayReference) {
        this.gatewayReference = gatewayReference;
    }

    String getFailureReason() {
        return failureReason;
    }

    void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    long getRefundedMinor() {
        return refundedMinor;
    }

    void setRefundedMinor(long refundedMinor) {
        this.refundedMinor = refundedMinor;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    long getVersion() {
        return version;
    }

    List<PaymentAttemptEntity> getAttempts() {
        return attempts;
    }

    List<PaymentRefundEntity> getRefunds() {
        return refunds;
    }
}
