package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * {@link EventPublisher} en memoria: guarda los últimos {@value #CAPACITY} eventos para que se
 * puedan consultar con {@link #recent()}; cuando se llena, descarta los más antiguos.
 * <p>
 * Es provisional: no reparte los eventos a nadie ni sobrevive a un reinicio. El outbox de
 * verdad (una tabla en la misma transacción que el pago y un proceso que los reparte) llega en
 * la fase 4b. Es seguro para hilos.
 */
public final class InMemoryEventPublisher implements EventPublisher {

    /** Cuántos eventos se guardan como mucho. */
    public static final int CAPACITY = 1000;

    private final Deque<DomainEvent> events = new ArrayDeque<>();

    @Override
    public synchronized void publish(List<DomainEvent> batch) {
        Objects.requireNonNull(batch, "la lista de eventos no puede ser nula");
        for (DomainEvent event : batch) {
            events.addLast(Objects.requireNonNull(event, "no se puede publicar un evento nulo"));
            if (events.size() > CAPACITY) {
                events.removeFirst();
            }
        }
    }

    /** Los últimos eventos publicados, del más antiguo al más reciente; como mucho {@value #CAPACITY}. */
    public synchronized List<DomainEvent> recent() {
        return List.copyOf(events);
    }
}
