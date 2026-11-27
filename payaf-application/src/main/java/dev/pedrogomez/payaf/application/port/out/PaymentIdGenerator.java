package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.PaymentId;

/**
 * Puerto de salida (driven): genera identificadores de pago.
 * <p>
 * El dominio no genera identidades (ver {@link PaymentId}); la aplicación las pide por aquí
 * para que los tests usen valores previsibles y producción use UUID aleatorios (o v7, que
 * se ordenan por tiempo), sin que el caso de uso cambie.
 */
public interface PaymentIdGenerator {

    PaymentId next();
}
