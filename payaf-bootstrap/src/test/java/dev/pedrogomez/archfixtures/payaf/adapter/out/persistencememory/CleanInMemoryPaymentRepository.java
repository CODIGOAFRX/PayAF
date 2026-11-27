package dev.pedrogomez.archfixtures.payaf.adapter.out.persistencememory;

import dev.pedrogomez.archfixtures.payaf.application.port.out.CleanPaymentRepository;
import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;
import java.util.ArrayList;
import java.util.List;

/** Adaptador de salida limpio: implementa el puerto en memoria. */
public class CleanInMemoryPaymentRepository implements CleanPaymentRepository {

    private final List<CleanMoney> saved = new ArrayList<>();

    @Override
    public void save(CleanMoney money) {
        saved.add(money);
    }
}
