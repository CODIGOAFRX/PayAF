package dev.pedrogomez.archfixtures.payaf.domain;

import jakarta.persistence.Entity;

/** Viola la regla: el dominio se anota como entidad JPA (jakarta). */
@Entity
public class DomainUsesJakartaPersistenceViolation {
}
