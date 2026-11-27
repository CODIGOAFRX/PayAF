package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.OutboxReader;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link EventPublisher} y {@link OutboxReader} en memoria: un outbox sin base de datos, para que
 * el relay y el panel funcionen igual con el perfil {@code memory} que con {@code postgres}.
 * <p>
 * Cada evento publicado se guarda dos veces:
 * <ul>
 *   <li>en la cola de pendientes, con un identificador creciente, hasta que el relay lo lee
 *       ({@link #fetchUnpublished}) y lo marca como publicado ({@link #markPublished});</li>
 *   <li>en el histórico de los últimos {@value #CAPACITY}, para consultarlo con {@link #recent()}.</li>
 * </ul>
 * Las dos tienen como mucho {@value #CAPACITY} elementos y, al llenarse, descartan los más
 * antiguos: si nadie hace de relay, la memoria no crece sin límite (se pierden eventos que el
 * PostgreSQL sí conservaría). Es seguro para hilos. No hay rollback (ver
 * {@link InMemoryTransactionRunner}): un evento publicado se queda aunque la transacción falle después.
 */
public final class InMemoryEventPublisher implements EventPublisher, OutboxReader {

    /** Cuántos eventos se guardan como mucho, en el histórico y en la cola de pendientes. */
    public static final int CAPACITY = 1000;

    private final Deque<DomainEvent> events = new ArrayDeque<>();
    private final Deque<OutboxMessage> pending = new ArrayDeque<>();
    private long nextId = 1;

    @Override
    public synchronized void publish(List<DomainEvent> batch) {
        Objects.requireNonNull(batch, "la lista de eventos no puede ser nula");
        for (DomainEvent event : batch) {
            events.addLast(Objects.requireNonNull(event, "no se puede publicar un evento nulo"));
            if (events.size() > CAPACITY) {
                events.removeFirst();
            }
            pending.addLast(new OutboxMessage(nextId++, PublishedEvent.from(event)));
            if (pending.size() > CAPACITY) {
                pending.removeFirst();
            }
        }
    }

    /** Los últimos eventos publicados, del más antiguo al más reciente; como mucho {@value #CAPACITY}. */
    public synchronized List<DomainEvent> recent() {
        return List.copyOf(events);
    }

    @Override
    public synchronized List<OutboxMessage> fetchUnpublished(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1: " + limit);
        }
        List<OutboxMessage> result = new ArrayList<>(Math.min(limit, pending.size()));
        for (OutboxMessage message : pending) {
            if (result.size() == limit) {
                break;
            }
            result.add(message);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void markPublished(Collection<Long> ids) {
        Set<Long> published = new HashSet<>(Objects.requireNonNull(ids, "los identificadores no pueden ser nulos"));
        pending.removeIf(message -> published.contains(message.id()));
    }

    /** Cuántos eventos esperan al relay. */
    public synchronized int pendingCount() {
        return pending.size();
    }
}
