package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionRunner} con el {@link TransactionTemplate} de Spring: una transacción de
 * verdad, con rollback. Si ya hay una abierta en el hilo, el trabajo se une a ella
 * ({@code PROPAGATION_REQUIRED}). Las excepciones no comprobadas del trabajo deshacen la
 * transacción y llegan tal cual a quien llamó, como pide el puerto.
 * <p>
 * Es el motivo de que la aplicación tenga este puerto en lugar de {@code @Transactional}: el
 * caso de uso marca dónde empieza y acaba cada transacción sin saber que existe Spring.
 */
public final class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    public SpringTransactionRunner(TransactionTemplate template) {
        this.template = Objects.requireNonNull(template);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "el trabajo no puede ser nulo");
        return template.execute(status -> work.get());
    }
}
