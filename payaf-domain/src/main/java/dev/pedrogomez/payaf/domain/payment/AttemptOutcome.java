package dev.pedrogomez.payaf.domain.payment;

/**
 * Resultado de pedir una autorización a una pasarela.
 * <p>
 * Es dominio porque el negocio trata cada resultado de forma distinta: APPROVED autoriza
 * el pago; DECLINED es una negativa del emisor (fondos insuficientes, tarjeta bloqueada) en
 * la que la pasarela ha funcionado bien; ERROR y TIMEOUT son fallos técnicos de la pasarela,
 * que justifican probar con otra y que cuentan para su circuit breaker.
 */
public enum AttemptOutcome {

    APPROVED,
    DECLINED,
    ERROR,
    TIMEOUT;

    /** Fallo técnico de la pasarela, no del pago: ERROR o TIMEOUT. DECLINED no lo es. */
    public boolean isTechnicalFailure() {
        return this == ERROR || this == TIMEOUT;
    }
}
