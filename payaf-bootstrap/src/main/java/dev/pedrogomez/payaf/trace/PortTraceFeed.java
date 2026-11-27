package dev.pedrogomez.payaf.trace;

import dev.pedrogomez.payaf.adapter.out.outbox.InProcessEventBroadcaster;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * El canal de los eventos {@code port.touched}: un broadcaster en proceso propio, aparte del que
 * reparte el outbox.
 * <p>
 * <strong>Por qué aparte.</strong> La traza no es un evento de dominio: no pasa por el outbox, no se
 * guarda y no espera al relay (llega mientras la operación ocurre). Reutiliza el mismo broadcaster
 * porque ya resuelve lo difícil (una cola acotada por suscriptor, para que un navegador lento no
 * frene a nadie) y el SSE mezcla los dos canales (ver {@code EventStreamConfig}). El identificador de
 * cada mensaje es un contador propio que no sale por el SSE.
 */
public final class PortTraceFeed implements Consumer<PortTracer.PortTouch> {

    public static final String EVENT_TYPE = "port.touched";
    public static final String AGGREGATE_TYPE = "trace";

    private final InProcessEventBroadcaster broadcaster = new InProcessEventBroadcaster();
    private final AtomicLong ids = new AtomicLong();
    private final Clock clock;

    public PortTraceFeed(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void accept(PortTracer.PortTouch touch) {
        if (broadcaster.subscriberCount() == 0) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("correlationId", touch.correlationId());
        payload.put("side", touch.side().name().toLowerCase(Locale.ROOT));
        payload.put("port", touch.port());
        payload.put("adapter", touch.adapter());
        payload.put("impl", touch.impl());
        payload.put("operation", touch.operation());
        payload.put("outcome", touch.outcome());
        payload.put("code", touch.code());
        payload.put("ms", touch.ms());
        payload.put("seq", touch.seq());
        broadcaster.deliver(new OutboxMessage(ids.incrementAndGet(),
                new PublishedEvent(AGGREGATE_TYPE, touch.correlationId(), EVENT_TYPE, payload, clock.instant())));
    }

    /** Suscribe un consumidor a la traza, desde ahora. */
    public AutoCloseable subscribe(Consumer<OutboxMessage> consumer) {
        return broadcaster.subscribe(consumer);
    }
}
