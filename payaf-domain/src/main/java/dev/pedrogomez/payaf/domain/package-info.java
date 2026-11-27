/**
 * Núcleo del dominio de PayAF: entidades, objetos de valor y reglas de negocio
 * del enrutado de pagos.
 * <p>
 * Es el centro del hexágono. Está escrito en Java puro y no conoce nada de lo que
 * lo rodea: ni Spring, ni JPA, ni Jackson, ni HTTP. Todas las demás capas dependen
 * de él y él no depende de ninguna.
 */
package dev.pedrogomez.payaf.domain;
