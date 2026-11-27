package dev.pedrogomez.archfixtures.payaf.domain;

import tools.jackson.databind.ObjectMapper;

/**
 * Viola la regla «el dominio no depende de Jackson» con Jackson 3, que es el que trae
 * Spring Boot 4 y vive en el paquete {@code tools.jackson}, no en {@code com.fasterxml}.
 */
public class DomainUsesJackson3Violation {

    public String toJson(Object value) {
        return new ObjectMapper().writeValueAsString(value);
    }
}
