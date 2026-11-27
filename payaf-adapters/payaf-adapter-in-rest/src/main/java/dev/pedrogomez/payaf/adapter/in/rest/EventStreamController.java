package dev.pedrogomez.payaf.adapter.in.rest;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/events/stream}: los eventos de dominio en directo, por Server-Sent Events.
 * <p>
 * Cada conexión se suscribe al {@link EventFeed} y reenvía cada evento como un mensaje SSE con
 * {@code id} (la posición en el outbox, para que el cliente detecte repetidos), {@code event} (el
 * tipo, p. ej. {@code PaymentAuthorized}) y {@code data} (JSON). Al cerrarse la conexión, por el
 * cliente, por error o por tiempo, se cancela la suscripción.
 */
@RestController
public class EventStreamController {

    /** Tras este tiempo se cierra la conexión; {@code EventSource} se reconecta solo. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final EventFeed feed;

    public EventStreamController(EventFeed feed) {
        this.feed = feed;
    }

    @GetMapping(path = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() throws IOException {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        Runnable unsubscribe = () -> {
            AutoCloseable s = subscription.getAndSet(null);
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // Ya estaba cerrada.
                }
            }
        };
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(e -> unsubscribe.run());
        // Un comentario inicial para que el cliente reciba las cabeceras al momento.
        emitter.send(SseEmitter.event().comment("conectado"));
        subscription.set(feed.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                unsubscribe.run();
                emitter.completeWithError(e);
            }
        }));
        return emitter;
    }
}
