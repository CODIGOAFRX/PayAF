/**
 * Capa de aplicación de PayAF.
 * <p>
 * Orquesta el dominio para resolver los casos de uso (crear un pago, enrutarlo a un
 * PSP, hacer failover...). Define los puertos por los que entra y sale el mundo
 * exterior, pero no sabe quién los implementa. Solo depende del dominio y no lleva
 * anotaciones de Spring: el cableado se hace en {@code payaf-bootstrap}.
 */
package dev.pedrogomez.payaf.application;
