package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.OutboxReader;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OutboxReader} sobre la tabla {@code outbox}.
 * <p>
 * {@link #fetchUnpublished} usa {@code FOR UPDATE SKIP LOCKED}: bloquea las filas que devuelve
 * hasta que acabe la transacción del relay, y otro relay que lea a la vez (otra réplica) se salta
 * las bloqueadas en vez de esperar o repetirlas. Así varios relays reparten el trabajo sin
 * coordinarse y sin entregar dos veces la misma fila (salvo si una transacción se deshace, que es
 * justo cuando hay que reintentar).
 */
public final class JdbcOutboxReader implements OutboxReader {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOutboxReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<OutboxMessage> fetchUnpublished(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1: " + limit);
        }
        return jdbc.query("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, occurred_at
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""",
                (rs, row) -> new OutboxMessage(rs.getLong("id"), new PublishedEvent(
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                        json.readValue(rs.getString("payload"), MAP), rs.getTimestamp("occurred_at").toInstant())),
                limit);
    }

    @Override
    public void markPublished(Collection<Long> ids) {
        Objects.requireNonNull(ids, "los identificadores no pueden ser nulos");
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ANY (?)",
                (Object) ids.toArray(Long[]::new));
    }
}
