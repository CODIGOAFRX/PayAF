package dev.pedrogomez.archfixtures.payaf.domain;

import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencememory.CleanInMemoryPaymentRepository;

/** Viola la regla: el dominio conoce un adaptador. */
public class DomainUsesAdapterViolation {

    private final CleanInMemoryPaymentRepository repository = new CleanInMemoryPaymentRepository();
}
