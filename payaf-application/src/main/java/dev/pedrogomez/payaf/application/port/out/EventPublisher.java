package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.event.DomainEvent;
import java.util.List;

/**
 * Puerto de salida (driven): publica los eventos de dominio hacia fuera (panel por SSE,
 * otros sistemas).
 * <p>
 * El adaptador previsto es un <em>outbox</em>: guarda los eventos en una tabla de la misma
 * base de datos y otro proceso los reparte después. Por eso el contrato exige que
 * <strong>se llame dentro de la misma transacción que el {@code save} del agregado</strong>
 * que los produjo: o se confirman el cambio y sus eventos, o ninguno. Publicar fuera de la
 * transacción permitiría anunciar un pago que luego no se guardó, o guardar uno sin avisar.
 */
public interface EventPublisher {

    /** Publica los eventos en el orden dado. Una lista vacía no hace nada. */
    void publish(List<DomainEvent> events);
}
