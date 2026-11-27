package dev.pedrogomez.archfixtures.payaf.adapter.in.rest;

import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa.CleanJpaPaymentRepository;

/** Viola la regla: el adaptador REST importa el adaptador JPA. */
public class RestUsesJpaAdapterViolation {

    private final CleanJpaPaymentRepository repository = new CleanJpaPaymentRepository();
}
