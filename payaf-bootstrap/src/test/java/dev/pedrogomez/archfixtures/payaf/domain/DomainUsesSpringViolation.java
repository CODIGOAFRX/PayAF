package dev.pedrogomez.archfixtures.payaf.domain;

import org.springframework.util.Assert;

/** Viola la regla: el dominio importa Spring. */
public class DomainUsesSpringViolation {

    public void check(Object value) {
        Assert.notNull(value, "valor obligatorio");
    }
}
