/**
 * Fixtures de ArchUnit: réplica en miniatura de la estructura de paquetes de PayAF
 * (domain, application, adapter.in.*, adapter.out.*). Las clases {@code Clean*} respetan
 * las reglas del hexágono y las {@code *Violation} incumplen una regla concreta.
 * <p>
 * Viven fuera de {@code dev.pedrogomez.payaf} a propósito: así ni el escaneo de Spring
 * ni {@code HexagonalArchitectureTest} las ven. Solo las importa
 * {@code HexagonalArchitectureRulesTest} con ClassFileImporter.
 */
package dev.pedrogomez.archfixtures.payaf;
