package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link PaymentRepository} en memoria, seguro para hilos.
 * <p>
 * <strong>Copias defensivas.</strong> Nunca guarda ni devuelve la instancia que le pasan: al
 * guardar y al leer reconstruye el agregado con {@link Payment#rehydrate}, como haría un
 * repositorio de base de datos al leer filas. Así quien tiene un pago en la mano no puede
 * cambiar lo guardado sin pasar por {@link #save}, y dos peticiones que leen el mismo pago
 * trabajan sobre instancias distintas. La copia tampoco arrastra los eventos pendientes.
 * <p>
 * <strong>Versión optimista.</strong> Cumple el contrato del puerto: dentro de un
 * {@link ConcurrentMap#compute} (atómico por pago) compara {@link Payment#version()} con la
 * guardada (0 si no hay ninguna); si no coinciden lanza
 * {@link ConcurrentPaymentModificationException} sin escribir nada; si coinciden guarda una
 * copia con la versión siguiente y llama a {@link Payment#markPersisted(long)}.
 * <p>
 * Las consultas recorren todos los pagos: es un almacén para la demo y los tests, no para
 * millones de filas.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentMap<PaymentId, Payment> rows = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        Objects.requireNonNull(payment, "el pago no puede ser nulo");
        Payment stored = rows.compute(payment.id(), (id, previous) -> {
            long storedVersion = previous == null ? 0 : previous.version();
            if (payment.version() != storedVersion) {
                throw new ConcurrentPaymentModificationException(id);
            }
            return copy(payment, storedVersion + 1);
        });
        payment.markPersisted(stored.version());
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        Objects.requireNonNull(id, "el identificador no puede ser nulo");
        return Optional.ofNullable(rows.get(id)).map(InMemoryPaymentRepository::copy);
    }

    @Override
    public Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(gatewayReference, "la referencia no puede ser nula");
        return rows.values().stream()
                .filter(p -> p.authorizedBy().filter(gateway::equals).isPresent()
                        && p.gatewayReference().filter(gatewayReference::equals).isPresent())
                .findFirst()
                .map(InMemoryPaymentRepository::copy);
    }

    @Override
    public List<Payment> findRecent(int limit) {
        requirePositive(limit);
        return rows.values().stream()
                .sorted(Comparator.comparing(Payment::createdAt).reversed()
                        .thenComparing(p -> p.id().value()))
                .limit(limit)
                .map(InMemoryPaymentRepository::copy)
                .toList();
    }

    @Override
    public List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "el corte no puede ser nulo");
        requirePositive(limit);
        return rows.values().stream()
                .filter(p -> p.status() == PaymentStatus.PENDING && p.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Payment::updatedAt).thenComparing(p -> p.id().value()))
                .limit(limit)
                .map(InMemoryPaymentRepository::copy)
                .toList();
    }

    /** Cuántos pagos hay guardados. */
    public int size() {
        return rows.size();
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1: " + limit);
        }
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
