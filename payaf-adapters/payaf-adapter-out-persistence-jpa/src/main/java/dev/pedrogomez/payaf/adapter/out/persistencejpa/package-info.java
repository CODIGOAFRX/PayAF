/**
 * Adaptador de salida (driven) de persistencia con JPA y PostgreSQL.
 * <p>
 * Implementa los puertos de persistencia (pagos, idempotencia, breakers, notificaciones procesadas,
 * transacciones) y el outbox ({@code EventPublisher} y {@code OutboxReader}). Las entidades JPA
 * viven aquí y se traducen al dominio con un mapeo explícito ({@code PaymentEntityMapper}): el
 * dominio nunca lleva anotaciones de persistencia (ver {@code PaymentEntity}). Donde hace falta
 * SQL de PostgreSQL ({@code ON CONFLICT}, {@code FOR UPDATE SKIP LOCKED}) se usa
 * {@code JdbcTemplate} en la misma transacción. El esquema lo gestiona Flyway. Se activa con el
 * perfil {@code postgres}.
 */
package dev.pedrogomez.payaf.adapter.out.persistencejpa;
