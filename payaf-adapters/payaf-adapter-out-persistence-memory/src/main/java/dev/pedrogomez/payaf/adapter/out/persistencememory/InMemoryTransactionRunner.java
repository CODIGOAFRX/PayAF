package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * {@link TransactionRunner} para el almacén en memoria: <strong>serializa</strong> las
 * transacciones con un único cerrojo, pero <strong>NO las deshace</strong>.
 * <p>
 * Lo que sí da: aislamiento. Mientras una transacción está dentro, ninguna otra entra, así que
 * nadie ve los cambios a medias de otra (como un nivel SERIALIZABLE muy bruto). El cerrojo es
 * reentrante: una transacción anidada se une a la que está abierta en el hilo. Nunca se tiene
 * durante una llamada a una pasarela, porque los servicios no llaman a ninguna con una
 * transacción abierta; por eso el cuello de botella es pequeño.
 * <p>
 * <strong>Limitación: no hay rollback.</strong> Si el trabajo lanza una excepción a medias, lo
 * que ya se hubiera escrito se queda escrito; la excepción llega tal cual a quien llamó, como
 * pide el puerto. Los casos de uso están pensados para que esto casi nunca importe (la
 * escritura que puede fallar por concurrencia, {@code PaymentRepository.save} con versión, es la
 * primera y no escribe nada si falla), pero si, por ejemplo, fallara la publicación de eventos
 * después de guardar el pago, el pago quedaría guardado sin sus eventos. Es aceptable para la
 * demo y los tests; el perfil {@code postgres} sí tendrá transacciones de verdad.
 */
public final class InMemoryTransactionRunner implements TransactionRunner {

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "el trabajo no puede ser nulo");
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** Si el hilo actual está dentro de una transacción. */
    public boolean isActive() {
        return lock.isHeldByCurrentThread();
    }
}
