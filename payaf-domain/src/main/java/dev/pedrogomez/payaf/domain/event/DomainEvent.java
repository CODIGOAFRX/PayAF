package dev.pedrogomez.payaf.domain.event;

import java.time.Instant;

/**
 * Algo relevante para el negocio que ya ha ocurrido en el dominio.
 * <p>
 * Los eventos son dominio porque hablan el idioma del negocio («pago autorizado»,
 * «circuito abierto») y los producen los agregados al cambiar de estado. El dominio no
 * sabe quién los escucha: los acumula y la aplicación los recoge con {@code pullEvents()}
 * para publicarlos por un puerto de salida (outbox, SSE del panel...). Así el agregado no
 * depende de ninguna infraestructura de mensajería.
 * <p>
 * Es una jerarquía sellada en dos ramas porque hay dos familias que no comparten clave:
 * <ul>
 *   <li>{@link PaymentEvent}: le pasan a un pago concreto y siempre tienen {@code paymentId()}.</li>
 *   <li>{@link GatewayHealthEvent}: hablan de la salud de una pasarela (su circuit breaker)
 *       y no pertenecen a ningún pago; su clave es {@code gateway()}.</li>
 * </ul>
 * Poner {@code paymentId()} en la raíz obligaría a que los eventos del circuito devolvieran
 * {@code null} o un {@code Optional} vacío, que es mentir sobre su forma. Con dos ramas
 * selladas, un {@code switch} sobre {@code DomainEvent} sigue siendo exhaustivo y el
 * compilador avisa si aparece un evento nuevo sin tratar.
 */
public sealed interface DomainEvent permits PaymentEvent, GatewayHealthEvent {

    /** Cuándo ocurrió, según el reloj que pasó quien provocó el cambio. */
    Instant occurredAt();
}
