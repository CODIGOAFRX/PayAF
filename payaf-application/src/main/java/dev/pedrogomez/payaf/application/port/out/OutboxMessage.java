package dev.pedrogomez.payaf.application.port.out;

import java.util.Objects;

/**
 * Un evento guardado en el outbox y pendiente de repartir.
 *
 * @param id    posición en el outbox. Crece con cada evento, así que sirve para ordenarlos, como
 *              {@code id:} del SSE y para marcarlo como publicado
 * @param event el evento en su forma publicada
 */
public record OutboxMessage(long id, PublishedEvent event) {

    public OutboxMessage {
        Objects.requireNonNull(event, "el evento no puede ser nulo");
        if (id < 1) {
            throw new IllegalArgumentException("El identificador del outbox debe ser positivo: " + id);
        }
    }
}
