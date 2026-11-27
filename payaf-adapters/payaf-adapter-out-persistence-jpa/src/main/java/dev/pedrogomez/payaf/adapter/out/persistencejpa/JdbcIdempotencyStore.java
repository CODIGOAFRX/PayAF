package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IdempotencyStore} sobre la tabla {@code idempotency_keys}, con SQL directo.
 * <p>
 * <strong>Atomicidad de {@link #tryReserve}.</strong> El «reservar si no existe» es un
 * {@code INSERT ... ON CONFLICT (key) DO NOTHING}: la clave primaria hace que, de dos peticiones
 * simultáneas con la misma clave, solo una inserte. La otra se queda esperando en el
 * {@code INSERT} a que la primera confirme (o deshaga) y entonces no inserta nada; el
 * {@code SELECT} posterior (en READ COMMITTED, con una foto nueva) ve la reserva de la primera.
 * Leer primero y luego insertar no serviría: las dos podrían leer «no existe». No se usa JPA
 * porque {@code ON CONFLICT} no tiene equivalente en JPQL y aquí no hay agregado que mapear.
 * <p>
 * Usa la conexión de la transacción en curso: {@code JpaTransactionManager} la comparte con
 * {@link JdbcTemplate}, así que la reserva se confirma o se deshace con el pago.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Reservation tryReserve(IdempotencyKey key, RequestFingerprint fingerprint, PaymentId candidate,
                                  Instant now) {
        Objects.requireNonNull(key, "la clave no puede ser nula");
        Objects.requireNonNull(fingerprint, "la huella no puede ser nula");
        Objects.requireNonNull(candidate, "el pago candidato no puede ser nulo");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        int inserted = jdbc.update("""
                        INSERT INTO idempotency_keys (key, fingerprint, payment_id, reserved_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (key) DO NOTHING""",
                key.value(), fingerprint.value(), candidate.value(), Timestamp.from(now));
        if (inserted == 1) {
            return new Reservation.Reserved();
        }
        return jdbc.queryForObject("SELECT fingerprint, payment_id, completed_at FROM idempotency_keys WHERE key = ?",
                (rs, row) -> {
                    RequestFingerprint stored = new RequestFingerprint(rs.getString("fingerprint"));
                    PaymentId paymentId = PaymentId.of(rs.getObject("payment_id", UUID.class));
                    return rs.getTimestamp("completed_at") != null
                            ? new Reservation.AlreadyCompleted(stored, paymentId)
                            : new Reservation.InProgress(stored, paymentId);
                },
                key.value());
    }

    /**
     * @throws IllegalStateException si la clave no está reservada para ese pago. Completar una
     *                               clave ya completada para el mismo pago no hace nada.
     */
    @Override
    public void complete(IdempotencyKey key, PaymentId paymentId) {
        Objects.requireNonNull(key, "la clave no puede ser nula");
        Objects.requireNonNull(paymentId, "el pago no puede ser nulo");
        int updated = jdbc.update("""
                        UPDATE idempotency_keys SET completed_at = COALESCE(completed_at, now())
                        WHERE key = ? AND payment_id = ?""",
                key.value(), paymentId.value());
        if (updated == 0) {
            throw new IllegalStateException("La clave «" + key + "» no está reservada para el pago " + paymentId);
        }
    }
}
