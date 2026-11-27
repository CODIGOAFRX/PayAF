package dev.pedrogomez.archfixtures.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.archfixtures.payaf.application.port.out.CleanPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;

/** Adaptador de salida limpio: implementa el puerto. */
public class CleanJpaPaymentRepository implements CleanPaymentRepository {

    @Override
    public void save(CleanMoney money) {
    }
}
