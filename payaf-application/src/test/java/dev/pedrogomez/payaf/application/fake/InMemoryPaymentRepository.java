package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio en memoria que guarda y devuelve copias, como haría una base de datos: si un
 * servicio cambia el pago y olvida guardarlo, el cambio no aparece al volver a cargarlo.
 * <p>
 * Es seguro para hilos y participa en el rollback de {@link FakeTransactionRunner}: un
 * {@code save} dentro de una transacción que se deshace no deja rastro.
 * <p>
 * Cumple el control optimista de versión del puerto: si la versión del pago que se guarda no
 * es la guardada (0 si no hay ninguna), lanza {@link ConcurrentPaymentModificationException}
 * sin escribir; si coincide, guarda con la versión siguiente y llama a
 * {@link Payment#markPersisted(long)}.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final FakeTransactionRunner transactions;
    private final Map<PaymentId, Payment> rows = new LinkedHashMap<>();
    private int saves;
    /**
     * Si además de la referencia de autorización se busca por la referencia de los intentos.
     * Simula un adaptador que indexa todas las referencias que dio la PSP, para poder llegar
     * a pagos FAILED cuyo intento rechazado traía referencia.
     */
    private volatile boolean matchAttemptReferences;

    public InMemoryPaymentRepository(FakeTransactionRunner transactions) {
        this.transactions = transactions;
    }

    public void matchAttemptReferences(boolean value) {
        this.matchAttemptReferences = value;
    }

    @Override
    public synchronized void save(Payment payment) {
        transactions.requireTransaction("PaymentRepository.save");
        Payment previous = rows.get(payment.id());
        long storedVersion = previous == null ? 0 : previous.version();
        if (payment.version() != storedVersion) {
            throw new ConcurrentPaymentModificationException(payment.id());
        }
        long newVersion = storedVersion + 1;
        rows.remove(payment.id());
        rows.put(payment.id(), copy(payment, newVersion));
        payment.markPersisted(newVersion);
        saves++;
        transactions.onRollback(() -> {
            synchronized (this) {
                rows.remove(payment.id());
                if (previous != null) {
                    rows.put(payment.id(), previous);
                }
                saves--;
            }
        });
    }

    @Override
    public synchronized Optional<Payment> findById(PaymentId id) {
        transactions.requireTransaction("PaymentRepository.findById");
        return Optional.ofNullable(rows.get(id)).map(InMemoryPaymentRepository::copy);
    }

    @Override
    public synchronized Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference) {
        transactions.requireTransaction("PaymentRepository.findByGatewayReference");
        return rows.values().stream()
                .filter(p -> matches(p, gateway, gatewayReference))
                .findFirst()
                .map(InMemoryPaymentRepository::copy);
    }

    private boolean matches(Payment p, GatewayId gateway, String reference) {
        if (p.authorizedBy().equals(Optional.of(gateway)) && p.gatewayReference().equals(Optional.of(reference))) {
            return true;
        }
        if (!matchAttemptReferences) {
            return false;
        }
        for (GatewayAttempt attempt : p.attempts()) {
            if (attempt.gateway().equals(gateway) && attempt.gatewayReference().equals(Optional.of(reference))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized List<Payment> findRecent(int limit) {
        transactions.requireTransaction("PaymentRepository.findRecent");
        List<Payment> all = new ArrayList<>(rows.values());
        Collections.reverse(all);
        return all.stream().limit(limit).map(InMemoryPaymentRepository::copy).toList();
    }

    @Override
    public synchronized List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
        transactions.requireTransaction("PaymentRepository.findPendingOlderThan");
        return rows.values().stream()
                .filter(p -> p.status() == PaymentStatus.PENDING && p.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Payment::updatedAt))
                .limit(limit)
                .map(InMemoryPaymentRepository::copy)
                .toList();
    }

    /** Lectura directa para los asertos, sin pasar por la comprobación de transacción. */
    public synchronized Payment stored(PaymentId id) {
        Payment row = rows.get(id);
        return row == null ? null : copy(row);
    }

    public synchronized List<Payment> all() {
        return rows.values().stream().map(InMemoryPaymentRepository::copy).toList();
    }

    public synchronized int size() {
        return rows.size();
    }

    public synchronized int saves() {
        return saves;
    }

    private static Payment copy(Payment p) {
        return copy(p, p.version());
    }

    private static Payment copy(Payment p, long version) {
        return Payment.rehydrate(p.id(), p.request(), p.status(), p.attempts(), p.authorizedBy().orElse(null),
                p.gatewayReference().orElse(null), p.failureReason().orElse(null), p.refundedAmount(), p.refunds(),
                p.createdAt(), p.updatedAt(), version);
    }
}
