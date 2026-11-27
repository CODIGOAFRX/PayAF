package dev.pedrogomez.payaf.adapter.in.rest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * La fuente de eventos en directo que necesita el SSE ({@code GET /api/events/stream}).
 * <p>
 * <strong>Por qué existe esta interfaz y no se usa el broadcaster del outbox.</strong> Los eventos
 * los reparte el adaptador {@code adapter.out.outbox}, pero un adaptador nunca importa otro (lo
 * vigila ArchUnit): si la REST dependiera del outbox, cambiar el reparto (por Kafka, por ejemplo)
 * obligaría a tocar la API. Así que la REST declara aquí lo poco que necesita, con su propio tipo
 * ({@link FeedEvent}), y es bootstrap, que sí conoce a los dos, quien la implementa con un puente
 * al broadcaster. Es la misma inversión de dependencias que los puertos de la aplicación, pero a
 * escala de adaptador: no es un puerto de la aplicación, porque la aplicación no la usa.
 */
public interface EventFeed {

    /**
     * Suscribe un oyente, que recibirá desde otro hilo los eventos que se publiquen desde ahora.
     *
     * @return se cierra para dejar de recibir
     */
    AutoCloseable subscribe(Consumer<FeedEvent> listener);

    /** Un evento tal como sale por el SSE. */
    record FeedEvent(long id, String type, String aggregateType, String aggregateId, Instant occurredAt,
                     Map<String, Object> payload) {

        public FeedEvent {
            Objects.requireNonNull(type);
            Objects.requireNonNull(aggregateType);
            Objects.requireNonNull(aggregateId);
            Objects.requireNonNull(occurredAt);
            Objects.requireNonNull(payload);
        }
    }
}
