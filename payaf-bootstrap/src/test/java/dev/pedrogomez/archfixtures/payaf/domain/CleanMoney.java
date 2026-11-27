package dev.pedrogomez.archfixtures.payaf.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Dominio limpio: solo Java estándar. */
public record CleanMoney(BigDecimal amount, String currency) {

    public CleanMoney {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
    }
}
