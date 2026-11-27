package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.port.out.ProcessedNotificationStore;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ProcessedNotificationStore} sobre {@code processed_notifications}. El marcar-si-no-estaba
 * es un {@code INSERT ... ON CONFLICT DO NOTHING} sobre la clave primaria (pasarela,
 * identificador): atómico, y dentro de la transacción que aplica la notificación, así que si esta
 * se deshace la marca desaparece con ella y la PSP puede reintentar.
 */
public final class JdbcProcessedNotificationStore implements ProcessedNotificationStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessedNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean markProcessed(GatewayId gateway, String notificationId) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(notificationId, "el identificador no puede ser nulo");
        return jdbc.update("""
                        INSERT INTO processed_notifications (gateway, notification_id) VALUES (?, ?)
                        ON CONFLICT DO NOTHING""",
                gateway.value(), notificationId) == 1;
    }
}
