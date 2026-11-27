package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.ProcessedNotificationStore;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProcessedNotificationStore} en memoria. El marcar-si-no-estaba es atómico porque lo
 * hace {@link Set#add} sobre un conjunto concurrente. La clave es la pareja
 * (pasarela, identificador): dos PSP pueden usar el mismo identificador de notificación.
 */
public final class InMemoryProcessedNotificationStore implements ProcessedNotificationStore {

    private record Key(GatewayId gateway, String notificationId) {
    }

    private final Set<Key> processed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markProcessed(GatewayId gateway, String notificationId) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(notificationId, "el identificador no puede ser nulo");
        return processed.add(new Key(gateway, notificationId));
    }

    public int size() {
        return processed.size();
    }
}
