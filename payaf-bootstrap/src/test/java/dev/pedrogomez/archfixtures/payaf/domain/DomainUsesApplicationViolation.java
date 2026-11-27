package dev.pedrogomez.archfixtures.payaf.domain;

import dev.pedrogomez.archfixtures.payaf.application.port.out.CleanPaymentRepository;

/** Viola la regla: el dominio conoce un puerto de la aplicación. */
public class DomainUsesApplicationViolation {

    private CleanPaymentRepository repository;
}
