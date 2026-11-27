/**
 * Servicios de aplicación: implementan los puertos de entrada.
 * <p>
 * Cada servicio coordina el dominio y los puertos de salida para completar un caso
 * de uso. Son clases Java normales, sin anotaciones, que reciben sus puertos (y el
 * {@link java.time.Clock}) por constructor; Spring las instancia desde {@code payaf-bootstrap}.
 * Las reglas de negocio no están aquí sino en el dominio: el servicio decide el orden de
 * los pasos, dónde empiezan y acaban las transacciones y a qué puertos se llama.
 */
package dev.pedrogomez.payaf.application.service;
