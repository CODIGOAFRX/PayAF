package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.DomainException;

/**
 * Los datos con los que se intenta reconstruir un pago ({@link Payment#rehydrate}) violan
 * los invariantes del agregado: un pago REFUNDED sin nada reembolsado, uno AUTHORIZED sin
 * pasarela, una fecha de actualización anterior a la de creación...
 * <p>
 * Es dominio porque es el agregado quien sabe qué combinaciones de estado son posibles.
 * Que salte significa que lo guardado está corrupto o que el adaptador lo ha mapeado mal;
 * no es culpa del cliente. Código estable: {@code "INCONSISTENT_STATE"}.
 */
public final class InconsistentPaymentStateException extends DomainException {

    public static final String CODE = "INCONSISTENT_STATE";

    public InconsistentPaymentStateException(String message) {
        super(CODE, message);
    }
}
