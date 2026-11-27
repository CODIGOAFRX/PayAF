package dev.pedrogomez.archfixtures.payaf.application.service;

import dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa.CleanJpaPaymentRepository;

/** Viola la regla: la aplicación instancia un adaptador en lugar de usar el puerto. */
public class ApplicationUsesAdapterViolation {

    private final CleanJpaPaymentRepository repository = new CleanJpaPaymentRepository();
}
