/**
 * El pago: el agregado {@link dev.pedrogomez.payaf.domain.payment.Payment}, su ciclo de
 * vida ({@link dev.pedrogomez.payaf.domain.payment.PaymentStatus}) y los valores que lo
 * describen.
 * <p>
 * Aquí está la regla más importante del sistema: qué le puede pasar a un pago y en qué
 * orden. Ninguna capa externa cambia el estado de un pago directamente; todas tienen
 * que pedírselo al agregado con un método de negocio, y el agregado decide.
 */
package dev.pedrogomez.payaf.domain.payment;
