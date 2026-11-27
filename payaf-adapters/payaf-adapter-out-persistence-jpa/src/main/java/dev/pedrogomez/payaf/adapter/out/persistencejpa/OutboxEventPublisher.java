package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link EventPublisher} con el patrón <strong>Outbox</strong>: no manda los eventos a ningún
 * sitio, los inserta en la tabla {@code outbox} dentro de la MISMA transacción que guarda el pago.
 * <p>
 * <strong>El problema que resuelve: la doble escritura.</strong> Al autorizar un pago hay que
 * hacer dos cosas: guardarlo en la base de datos y avisar (al panel, a contabilidad...). Son dos
 * sistemas, y no hay transacción que los abarque a los dos:
 * <ul>
 *   <li>si se guarda y luego se avisa, y el proceso muere entre medias, el pago existe pero nadie
 *       se entera;</li>
 *   <li>si se avisa y luego se guarda, y el guardado falla (un conflicto de versión, por ejemplo),
 *       se ha anunciado un pago que no existe.</li>
 * </ul>
 * Con el outbox solo hay una escritura, en una sola base de datos: el cambio y sus eventos se
 * confirman juntos o no se confirma ninguno. El reparto lo hace después otro proceso (el relay,
 * {@code RelayOutboxUseCase}), que lee la tabla y puede reintentar tantas veces como haga falta.
 * A cambio, la entrega es «al menos una vez» y llega con un pequeño retraso.
 * <p>
 * El payload es JSON (con Jackson, que se permite aquí, en el adaptador, y nunca en el dominio) a
 * partir de {@link PublishedEvent}, que es el formato publicado que fija la aplicación.
 *
 * @throws IllegalStateException en {@link #publish} si no hay una transacción abierta: publicar
 *                               fuera de ella rompería justo la garantía que da este adaptador
 */
public final class OutboxEventPublisher implements EventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void publish(List<DomainEvent> events) {
        Objects.requireNonNull(events, "la lista de eventos no puede ser nula");
        if (events.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El outbox se escribe dentro de la transacción del cambio que publica");
        }
        List<Object[]> rows = events.stream()
                .map(event -> PublishedEvent.from(Objects.requireNonNull(event, "no se puede publicar un evento nulo")))
                .map(e -> new Object[] {e.aggregateType(), e.aggregateId(), e.eventType(),
                        json.writeValueAsString(e.payload()), Timestamp.from(e.occurredAt())})
                .toList();
        jdbc.batchUpdate("""
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, occurred_at)
                VALUES (?, ?, ?, ?::jsonb, ?)""", rows);
    }
}
