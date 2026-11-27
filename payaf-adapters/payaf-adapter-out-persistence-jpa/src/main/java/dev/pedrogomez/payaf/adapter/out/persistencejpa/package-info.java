/**
 * Adaptador de salida (driven) de persistencia con JPA y PostgreSQL.
 * <p>
 * Implementa los puertos de salida de repositorio guardando los pagos en
 * PostgreSQL. Las entidades JPA viven aquí y se traducen a objetos del dominio, que
 * nunca llevan anotaciones de persistencia. El esquema lo gestiona Flyway.
 * Se activa con el perfil {@code postgres}.
 */
package dev.pedrogomez.payaf.adapter.out.persistencejpa;
