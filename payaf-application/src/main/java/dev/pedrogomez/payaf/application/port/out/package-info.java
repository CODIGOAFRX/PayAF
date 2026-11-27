/**
 * Puertos de salida (driven ports).
 * <p>
 * Interfaces que la aplicación necesita del exterior: guardar pagos, hablar con un
 * PSP, publicar eventos. Las implementan los adaptadores de salida (JPA, memoria,
 * simulador de pasarela, outbox), de modo que la aplicación nunca depende de ellos.
 */
package dev.pedrogomez.payaf.application.port.out;
