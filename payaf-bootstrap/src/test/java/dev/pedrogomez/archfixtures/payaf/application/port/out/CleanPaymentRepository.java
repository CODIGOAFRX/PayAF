package dev.pedrogomez.archfixtures.payaf.application.port.out;

import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;

/** Puerto de salida limpio: solo conoce el dominio. */
public interface CleanPaymentRepository {

    void save(CleanMoney money);
}
