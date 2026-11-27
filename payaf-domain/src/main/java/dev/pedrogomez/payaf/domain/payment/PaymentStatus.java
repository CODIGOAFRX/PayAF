package dev.pedrogomez.payaf.domain.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados del ciclo de vida de un pago y transiciones permitidas entre ellos.
 * <p>
 * Es dominio porque la máquina de estados ES el negocio: un pago no se puede capturar
 * sin haberlo autorizado ni reembolsar sin haberlo capturado. Declarar aquí las
 * transiciones deja la regla en un único sitio, fácil de leer y de probar:
 * <ul>
 *   <li>PENDING pasa a AUTHORIZED o FAILED.</li>
 *   <li>AUTHORIZED pasa a CAPTURED o CANCELED.</li>
 *   <li>CAPTURED pasa a PARTIALLY_REFUNDED o REFUNDED.</li>
 *   <li>PARTIALLY_REFUNDED admite otro reembolso parcial (se queda igual) o pasa a REFUNDED.</li>
 *   <li>FAILED, CANCELED y REFUNDED son terminales: de ahí no se sale.</li>
 * </ul>
 */
public enum PaymentStatus {

    /** Creado y todavía sin autorizar por ninguna pasarela. */
    PENDING,
    /** Una pasarela ha reservado el importe, pero aún no se ha cobrado. */
    AUTHORIZED,
    /** Cobrado por el total autorizado. */
    CAPTURED,
    /** Se ha devuelto una parte de lo capturado. */
    PARTIALLY_REFUNDED,
    /** Se ha devuelto todo lo capturado. Terminal. */
    REFUNDED,
    /** Ninguna pasarela lo ha autorizado. Terminal. */
    FAILED,
    /** Se anuló la autorización antes de capturar. Terminal. */
    CANCELED;

    /** Estados a los que se puede pasar desde este. Devuelve un conjunto nuevo en cada llamada. */
    public Set<PaymentStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(AUTHORIZED, FAILED);
            case AUTHORIZED -> EnumSet.of(CAPTURED, CANCELED);
            case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case REFUNDED, FAILED, CANCELED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return target != null && allowedTransitions().contains(target);
    }

    /** Un estado terminal no admite ninguna transición más. */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** Si en este estado el pago tiene dinero capturado (y, por tanto, algo se ha cobrado). */
    public boolean isCaptured() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
