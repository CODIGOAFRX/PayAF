/**
 * Puertos de entrada (driving ports).
 * <p>
 * Interfaces de los casos de uso que ofrece la aplicación, con sus comandos y resultados.
 * Los adaptadores de entrada (REST, webhooks, CLI) las invocan sin conocer su
 * implementación. Por aquí nunca sale el agregado {@code Payment}: sale una
 * {@link dev.pedrogomez.payaf.application.port.in.PaymentView} de solo lectura.
 */
package dev.pedrogomez.payaf.application.port.in;
