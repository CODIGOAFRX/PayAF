package dev.pedrogomez.payaf.domain.payment;

/**
 * Qué ha hecho {@link Payment#refund}. Es un enum y no un {@code boolean} porque se lee solo
 * en quien lo usa ({@code ALREADY_APPLIED} frente a un {@code false} que no dice qué es
 * falso) y porque deja sitio a más casos sin cambiar la firma.
 */
public enum RefundOutcome {
    /** El reembolso era nuevo y se ha aplicado. */
    APPLIED,
    /** Ya había un reembolso con esa referencia: no se ha cambiado nada ni se ha emitido ningún evento. */
    ALREADY_APPLIED
}
