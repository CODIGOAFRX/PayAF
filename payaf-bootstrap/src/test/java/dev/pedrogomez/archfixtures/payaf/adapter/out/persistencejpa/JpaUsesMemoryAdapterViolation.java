package dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencememory.CleanInMemoryPaymentRepository;

/** Viola la regla: el adaptador JPA delega en el adaptador en memoria. */
public class JpaUsesMemoryAdapterViolation {

    private final CleanInMemoryPaymentRepository fallback = new CleanInMemoryPaymentRepository();
}
