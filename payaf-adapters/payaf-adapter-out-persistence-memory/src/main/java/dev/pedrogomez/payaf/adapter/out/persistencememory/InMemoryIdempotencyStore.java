package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link IdempotencyStore} en memoria.
 * <p>
 * <strong>Atomicidad de {@link #tryReserve}.</strong> El «reservar si no existe» se hace dentro
 * de {@link ConcurrentMap#compute}, que para una misma clave ejecuta la función de una en una:
 * si dos peticiones llegan a la vez con la misma clave, la primera la crea y la segunda ve lo
 * que ha dejado la primera. Es el equivalente en memoria de la clave única de PostgreSQL. Un
 * {@code get} seguido de un {@code put} no serviría: las dos podrían ver «no existe».
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(RequestFingerprint fingerprint, PaymentId paymentId, Instant reservedAt, boolean completed) {
    }

    private final ConcurrentMap<IdempotencyKey, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Reservation tryReserve(IdempotencyKey key, RequestFingerprint fingerprint, PaymentId candidate,
                                  Instant now) {
        Objects.requireNonNull(key, "la clave no puede ser nula");
        Objects.requireNonNull(fingerprint, "la huella no puede ser nula");
        Objects.requireNonNull(candidate, "el pago candidato no puede ser nulo");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        Reservation[] result = new Reservation[1];
        entries.compute(key, (k, existing) -> {
            if (existing == null) {
                result[0] = new Reservation.Reserved();
                return new Entry(fingerprint, candidate, now, false);
            }
            result[0] = existing.completed()
                    ? new Reservation.AlreadyCompleted(existing.fingerprint(), existing.paymentId())
                    : new Reservation.InProgress(existing.fingerprint(), existing.paymentId());
            return existing;
        });
        return result[0];
    }

    /**
     * @throws IllegalStateException si la clave no está reservada para ese pago. Completar una
     *                               clave ya completada para el mismo pago no hace nada.
     */
    @Override
    public void complete(IdempotencyKey key, PaymentId paymentId) {
        Objects.requireNonNull(key, "la clave no puede ser nula");
        Objects.requireNonNull(paymentId, "el pago no puede ser nulo");
        entries.compute(key, (k, existing) -> {
            if (existing == null || !existing.paymentId().equals(paymentId)) {
                throw new IllegalStateException("La clave «" + key + "» no está reservada para el pago " + paymentId);
            }
            return new Entry(existing.fingerprint(), paymentId, existing.reservedAt(), true);
        });
    }

    /** Si la clave existe y está completada. */
    public boolean isCompleted(IdempotencyKey key) {
        return Optional.ofNullable(entries.get(key)).map(Entry::completed).orElse(false);
    }

    public int size() {
        return entries.size();
    }
}
