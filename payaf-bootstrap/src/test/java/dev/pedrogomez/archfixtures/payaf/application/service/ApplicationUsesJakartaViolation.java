package dev.pedrogomez.archfixtures.payaf.application.service;

import jakarta.persistence.EntityManager;

/** Viola «application solo depende de domain»: usa JPA (jakarta) directamente. */
public class ApplicationUsesJakartaViolation {

    private EntityManager entityManager;
}
