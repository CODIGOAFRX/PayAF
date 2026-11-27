package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Publicador que apunta los eventos y comprueba que se le llama dentro de una transacción.
 * Como un outbox, si la transacción se deshace los eventos publicados en ella desaparecen.
 */
public final class RecordingEventPublisher implements EventPublisher {

    private final FakeTransactionRunner transactions;
    private final List<DomainEvent> published = new ArrayList<>();
    private volatile java.util.function.Function<List<DomainEvent>, Throwable> failure = events -> null;

    /** Si la función devuelve algo para un lote, {@link #publish} lo lanza sin publicar nada. */
    public void failWhen(java.util.function.Function<List<DomainEvent>, Throwable> failure) {
        this.failure = failure;
    }

    public RecordingEventPublisher(FakeTransactionRunner transactions) {
        this.transactions = transactions;
    }

    @Override
    public synchronized void publish(List<DomainEvent> events) {
        transactions.requireTransaction("EventPublisher.publish");
        Throwable thrown = failure.apply(events);
        if (thrown != null) {
            throw FunctionalGateway.sneakyThrow(thrown);
        }
        List<DomainEvent> batch = List.copyOf(events);
        published.addAll(batch);
        transactions.onRollback(() -> {
            synchronized (this) {
                for (DomainEvent event : batch) {
                    // Por identidad: se quitan exactamente las instancias de este lote.
                    for (int i = published.size() - 1; i >= 0; i--) {
                        if (published.get(i) == event) {
                            published.remove(i);
                            break;
                        }
                    }
                }
            }
        });
    }

    public synchronized List<DomainEvent> published() {
        return List.copyOf(published);
    }

    public synchronized <E extends DomainEvent> List<E> published(Class<E> type) {
        return published.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
