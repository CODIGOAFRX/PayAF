package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Transacciones de mentira: cuenta cuántas se abren y sabe si hay una abierta <em>en el hilo
 * actual</em>, para que los demás fakes comprueben que se les llama dentro (repositorio,
 * outbox, almacenes) o fuera (pasarelas, breakers).
 * <p>
 * <strong>Simula el rollback.</strong> Los fakes que modifican estado apuntan con
 * {@link #onRollback} cómo deshacer cada cambio; si el trabajo de la transacción más externa
 * lanza, se deshacen en orden inverso, como haría la base de datos, y la excepción sale tal
 * cual. El registro de deshacer es por hilo, así que una transacción que se deshace no toca
 * lo que han hecho las de otros hilos.
 * <p>
 * Lleva además un diario de qué se hizo en cada transacción (o fuera de ellas) para poder
 * comprobar el reparto de las operaciones entre transacciones.
 */
public final class FakeTransactionRunner implements TransactionRunner {

    /** Una entrada del diario: {@code transaction} es 0 si la operación fue fuera de transacción. */
    public record Entry(int transaction, String what) {
    }

    private static final class Tx {
        final int id;
        int depth;
        final List<Runnable> undo = new ArrayList<>();

        Tx(int id) {
            this.id = id;
        }
    }

    private final ThreadLocal<Tx> current = new ThreadLocal<>();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final List<String> violations = new ArrayList<>();
    private final List<Entry> journal = new ArrayList<>();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Tx tx = current.get();
        boolean outermost = tx == null;
        if (outermost) {
            tx = new Tx(count.incrementAndGet());
            current.set(tx);
        }
        tx.depth++;
        try {
            return work.get();
        } catch (RuntimeException | Error e) {
            if (outermost) {
                rollback(tx);
            }
            throw e;
        } finally {
            tx.depth--;
            if (outermost) {
                current.remove();
            }
        }
    }

    private void rollback(Tx tx) {
        rollbacks.incrementAndGet();
        for (int i = tx.undo.size() - 1; i >= 0; i--) {
            tx.undo.get(i).run();
        }
        tx.undo.clear();
    }

    /** Apunta cómo deshacer un cambio si la transacción actual se deshace. Fuera de transacción no hace nada. */
    public void onRollback(Runnable undo) {
        Tx tx = current.get();
        if (tx != null) {
            tx.undo.add(undo);
        }
    }

    public boolean isActive() {
        return current.get() != null;
    }

    public int transactionCount() {
        return count.get();
    }

    public int rollbackCount() {
        return rollbacks.get();
    }

    /** Lo llaman las pasarelas y el almacén de breakers: no debe haber una transacción abierta. */
    public void requireNoTransaction(String what) {
        Tx tx = current.get();
        synchronized (this) {
            journal.add(new Entry(tx == null ? 0 : tx.id, what));
            if (tx != null) {
                violations.add(what + " con una transacción abierta");
            }
        }
    }

    /** Lo llaman repositorio, almacenes y publicador: debe haber una transacción abierta. */
    public void requireTransaction(String what) {
        Tx tx = current.get();
        synchronized (this) {
            journal.add(new Entry(tx == null ? 0 : tx.id, what));
            if (tx == null) {
                violations.add(what + " fuera de una transacción");
            }
        }
    }

    public synchronized List<String> violations() {
        return List.copyOf(violations);
    }

    public synchronized List<Entry> journal() {
        return List.copyOf(journal);
    }

    /** Lo que se hizo en la transacción {@code id}, en orden. */
    public synchronized List<String> journalOf(int id) {
        return journal.stream().filter(e -> e.transaction() == id).map(Entry::what).toList();
    }
}
