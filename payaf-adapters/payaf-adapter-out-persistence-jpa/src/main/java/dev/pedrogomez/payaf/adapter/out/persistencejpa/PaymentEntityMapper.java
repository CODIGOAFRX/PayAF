package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.Refund;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Traducción explícita entre el agregado {@link Payment} y sus filas ({@link PaymentEntity} y sus
 * hijas). Ida: copia campo a campo. Vuelta: siempre por {@link Payment#rehydrate}, que valida los
 * invariantes, de modo que una fila corrupta falla al cargarse y no tres pasos después.
 * <p>
 * Intentos y reembolsos solo se añaden: al actualizar se insertan los que faltan y nunca se
 * borran ni se reescriben los que ya estaban (borrar y volver a insertar chocaría con la
 * restricción única de los reembolsos, porque Hibernate inserta antes de borrar).
 */
final class PaymentEntityMapper {

    private PaymentEntityMapper() {
    }

    static Payment toDomain(PaymentEntity e) {
        Currency currency = Currency.valueOf(e.getCurrency());
        PaymentRequest request = new PaymentRequest(Money.of(e.getAmountMinor(), currency),
                CountryCode.of(e.getCountry()), e.getMerchantReference(), IdempotencyKey.of(e.getIdempotencyKey()));
        List<GatewayAttempt> attempts = e.getAttempts().stream()
                .map(a -> new GatewayAttempt(GatewayId.of(a.getGateway()), AttemptOutcome.valueOf(a.getOutcome()),
                        Duration.ofNanos(a.getLatencyNanos()), Optional.ofNullable(a.getCode()),
                        Optional.ofNullable(a.getGatewayReference()), a.getAttemptedAt()))
                .toList();
        List<Refund> refunds = e.getRefunds().stream()
                .map(r -> new Refund(r.getGatewayRefundReference(), Money.of(r.getAmountMinor(), currency),
                        r.getRefundedAt()))
                .toList();
        return Payment.rehydrate(PaymentId.of(e.getId()), request, PaymentStatus.valueOf(e.getStatus()), attempts,
                e.getAuthorizedBy() == null ? null : GatewayId.of(e.getAuthorizedBy()), e.getGatewayReference(),
                e.getFailureReason(), Money.of(e.getRefundedMinor(), currency), refunds, e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion() + 1);
    }

    /** Una entidad nueva con todo el pago. */
    static PaymentEntity toNewEntity(Payment payment) {
        PaymentEntity entity = new PaymentEntity(payment.id().value());
        PaymentRequest request = payment.request();
        entity.setAmountMinor(request.amount().amountMinor());
        entity.setCurrency(request.amount().currency().name());
        entity.setCountry(request.country().value());
        entity.setMerchantReference(request.merchantReference());
        entity.setIdempotencyKey(request.key().value());
        entity.setCreatedAt(payment.createdAt());
        copyInto(payment, entity);
        return entity;
    }

    /**
     * Copia en la entidad lo que puede cambiar del pago: estado, autorización, fallo, reembolsos,
     * fecha de actualización e intentos nuevos. La orden (importe, país, referencia, clave) no
     * cambia nunca.
     *
     * @throws IllegalStateException si el pago tiene menos intentos que la fila: el agregado nunca
     *                               los quita, así que la instancia no viene de esta fila
     */
    static void copyInto(Payment payment, PaymentEntity entity) {
        entity.setStatus(payment.status().name());
        entity.setAuthorizedBy(payment.authorizedBy().map(GatewayId::value).orElse(null));
        entity.setGatewayReference(payment.gatewayReference().orElse(null));
        entity.setFailureReason(payment.failureReason().orElse(null));
        entity.setRefundedMinor(payment.refundedAmount().amountMinor());
        entity.setUpdatedAt(payment.updatedAt());

        List<GatewayAttempt> attempts = payment.attempts();
        List<PaymentAttemptEntity> stored = entity.getAttempts();
        if (stored.size() > attempts.size()) {
            throw new IllegalStateException("El pago " + payment.id() + " tiene menos intentos que los guardados");
        }
        for (int i = stored.size(); i < attempts.size(); i++) {
            GatewayAttempt a = attempts.get(i);
            stored.add(new PaymentAttemptEntity(entity, i, a.gateway().value(), a.outcome().name(),
                    a.latency().toNanos(), a.code().orElse(null), a.gatewayReference().orElse(null), a.at()));
        }

        Set<String> known = new HashSet<>();
        entity.getRefunds().forEach(r -> known.add(r.getGatewayRefundReference()));
        for (Refund refund : payment.refunds()) {
            if (known.add(refund.gatewayRefundReference())) {
                entity.getRefunds().add(new PaymentRefundEntity(entity, refund.gatewayRefundReference(),
                        refund.amount().amountMinor(), refund.at()));
            }
        }
    }
}
