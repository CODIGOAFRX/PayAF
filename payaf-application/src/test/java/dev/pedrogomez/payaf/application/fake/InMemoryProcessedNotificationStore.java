package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.ProcessedNotificationStore;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.HashSet;
import java.util.Set;

/**
 * Notificaciones procesadas en memoria; marcar-si-no-estaba atómico con {@code synchronized}.
 * La marca participa en el rollback de {@link FakeTransactionRunner}: si la transacción que
 * aplica la notificación se deshace, la marca desaparece y la PSP puede reintentar.
 */
public final class InMemoryProcessedNotificationStore implements ProcessedNotificationStore {

    private final FakeTransactionRunner transactions;
    private final Set<String> processed = new HashSet<>();

    public InMemoryProcessedNotificationStore(FakeTransactionRunner transactions) {
        this.transactions = transactions;
    }

    @Override
    public synchronized boolean markProcessed(GatewayId gateway, String notificationId) {
        transactions.requireTransaction("ProcessedNotificationStore.markProcessed");
        String id = gateway + "/" + notificationId;
        boolean added = processed.add(id);
        if (added) {
            transactions.onRollback(() -> {
                synchronized (this) {
                    processed.remove(id);
                }
            });
        }
        return added;
    }

    public synchronized boolean isProcessed(GatewayId gateway, String notificationId) {
        return processed.contains(gateway + "/" + notificationId);
    }
}
