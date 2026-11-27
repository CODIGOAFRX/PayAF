package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.rest.EventFeed;
import dev.pedrogomez.payaf.adapter.out.outbox.InProcessEventBroadcaster;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import dev.pedrogomez.payaf.trace.PortTraceFeed;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El reparto de eventos, en los dos perfiles: el broadcaster en proceso ({@code EventStream} del
 * relay) y el puente que lo ofrece a la REST como {@link EventFeed}.
 * <p>
 * El puente vive aquí porque es lo único que puede conocer a la vez {@code adapter.in.rest} y
 * {@code adapter.out.outbox}: un adaptador nunca importa otro. Mezcla dos canales en el mismo SSE:
 * los eventos de dominio del outbox (con su posición como {@code id}) y la traza de puertos
 * ({@link PortTraceFeed}, con {@code id} 0). Cada canal entrega desde su propio hilo, así que el
 * oyente se llama de uno en uno.
 */
@Configuration(proxyBeanMethods = false)
public class EventStreamConfig {

    @Bean
    InProcessEventBroadcaster eventBroadcaster() {
        return new InProcessEventBroadcaster();
    }

    @Bean
    EventFeed eventFeed(InProcessEventBroadcaster broadcaster, PortTraceFeed trace) {
        return listener -> {
            Object lock = new Object();
            Consumer<EventFeed.FeedEvent> serialized = event -> {
                synchronized (lock) {
                    listener.accept(event);
                }
            };
            AutoCloseable domain = broadcaster.subscribe(message -> serialized.accept(toFeed(message, message.id())));
            AutoCloseable ports = trace.subscribe(message -> serialized.accept(toFeed(message, 0)));
            return () -> {
                try {
                    ports.close();
                } finally {
                    domain.close();
                }
            };
        };
    }

    private static EventFeed.FeedEvent toFeed(OutboxMessage message, long id) {
        PublishedEvent e = message.event();
        return new EventFeed.FeedEvent(id, e.eventType(), e.aggregateType(), e.aggregateId(), e.occurredAt(),
                e.payload());
    }
}
