package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Registro de claves en memoria; la atomicidad de reservar-si-no-existe la da
 * {@code synchronized}. Participa en el rollback de {@link FakeTransactionRunner}.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(RequestFingerprint fingerprint, PaymentId paymentId, boolean completed) {
    }

    private final FakeTransactionRunner transactions;
    private final Map<IdempotencyKey, Entry> entries = new HashMap<>();
    private int reservations;

    public InMemoryIdempotencyStore(FakeTransactionRunner transactions) {
        this.transactions = transactions;
    }

    @Override
    public synchronized Reservation tryReserve(IdempotencyKey key, RequestFingerprint fingerprint,
                                               PaymentId candidate, Instant now) {
        transactions.requireTransaction("IdempotencyStore.tryReserve");
        Entry existing = entries.get(key);
        if (existing == null) {
            entries.put(key, new Entry(fingerprint, candidate, false));
            reservations++;
            transactions.onRollback(() -> {
                synchronized (this) {
                    entries.remove(key);
                    reservations--;
                }
            });
            return new Reservation.Reserved();
        }
        return existing.completed()
                ? new Reservation.AlreadyCompleted(existing.fingerprint(), existing.paymentId())
                : new Reservation.InProgress(existing.fingerprint(), existing.paymentId());
    }

    @Override
    public synchronized void complete(IdempotencyKey key, PaymentId paymentId) {
        transactions.requireTransaction("IdempotencyStore.complete");
        Entry existing = entries.get(key);
        if (existing == null || !existing.paymentId().equals(paymentId)) {
            throw new IllegalStateException("No hay reserva de " + key + " para " + paymentId);
        }
        entries.put(key, new Entry(existing.fingerprint(), paymentId, true));
        transactions.onRollback(() -> {
            synchronized (this) {
                entries.put(key, existing);
            }
        });
    }

    public synchronized boolean isCompleted(IdempotencyKey key) {
        Entry entry = entries.get(key);
        return entry != null && entry.completed();
    }

    public synchronized boolean isReserved(IdempotencyKey key) {
        return entries.containsKey(key);
    }

    /** Cuántas veces se ha concedido una reserva nueva (sin contar las deshechas). */
    public synchronized int reservations() {
        return reservations;
    }
}
