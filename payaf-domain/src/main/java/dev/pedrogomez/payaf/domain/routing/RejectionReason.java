package dev.pedrogomez.payaf.domain.routing;

/**
 * Por qué la política de enrutado ha descartado una pasarela para un pago. Si hay varios
 * motivos, se anota el primero en este orden: DISABLED, CURRENCY_NOT_SUPPORTED,
 * COUNTRY_NOT_SUPPORTED y CIRCUIT_OPEN (el circuito solo se consulta si la pasarela podría
 * atender el pago).
 */
public enum RejectionReason {
    /** La pasarela está deshabilitada. */
    DISABLED,
    /** La pasarela no admite la divisa del pago. */
    CURRENCY_NOT_SUPPORTED,
    /** La pasarela no opera en el país del pago. */
    COUNTRY_NOT_SUPPORTED,
    /** El circuit breaker de la pasarela no admite tráfico ahora mismo. */
    CIRCUIT_OPEN
}
