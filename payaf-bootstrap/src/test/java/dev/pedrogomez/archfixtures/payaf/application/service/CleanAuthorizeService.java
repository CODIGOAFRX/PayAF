package dev.pedrogomez.archfixtures.payaf.application.service;

import dev.pedrogomez.archfixtures.payaf.application.port.out.CleanPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;

/** Caso de uso limpio: conoce el dominio y sus puertos. */
public class CleanAuthorizeService {

    private final CleanPaymentRepository repository;

    public CleanAuthorizeService(CleanPaymentRepository repository) {
        this.repository = repository;
    }

    public void authorize(CleanMoney money) {
        repository.save(money);
    }
}
