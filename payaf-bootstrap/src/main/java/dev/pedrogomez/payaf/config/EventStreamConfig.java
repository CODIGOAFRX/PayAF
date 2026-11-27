package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.rest.EventFeed;
import dev.pedrogomez.payaf.adapter.out.outbox.InProcessEventBroadcaster;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El reparto de eventos, en los dos perfiles: el broadcaster en proceso ({@code EventStream} del
 * relay) y el puente que lo ofrece a la REST como {@link EventFeed}.
 * <p>
 * El puente vive aquí porque es lo único que puede conocer a la vez {@code adapter.in.rest} y
 * {@code adapter.out.outbox}: un adaptador nunca importa otro.
 */
@Configuration(proxyBeanMethods = false)
public class EventStreamConfig {

    @Bean
    InProcessEventBroadcaster eventBroadcaster() {
        return new InProcessEventBroadcaster();
    }

    @Bean
    EventFeed eventFeed(InProcessEventBroadcaster broadcaster) {
        return listener -> broadcaster.subscribe(message -> {
            PublishedEvent e = message.event();
            listener.accept(new EventFeed.FeedEvent(message.id(), e.eventType(), e.aggregateType(), e.aggregateId(),
                    e.occurredAt(), e.payload()));
        });
    }
}
