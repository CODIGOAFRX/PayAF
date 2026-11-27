package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.port.in.RelayOutboxUseCase;
import dev.pedrogomez.payaf.application.port.out.EventStream;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.OutboxReader;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import java.util.List;
import java.util.Objects;

/**
 * Relay del outbox: lee los eventos pendientes, los entrega y los marca como publicados en una
 * sola transacción (ver {@link RelayOutboxUseCase}).
 */
public final class RelayOutboxService implements RelayOutboxUseCase {

    private final OutboxReader outbox;
    private final EventStream stream;
    private final TransactionRunner transactions;

    public RelayOutboxService(OutboxReader outbox, EventStream stream, TransactionRunner transactions) {
        this.outbox = Objects.requireNonNull(outbox);
        this.stream = Objects.requireNonNull(stream);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int relay(int batch) {
        if (batch < 1) {
            throw new IllegalArgumentException("El lote debe ser de al menos 1 mensaje: " + batch);
        }
        return transactions.inTransaction(() -> {
            List<OutboxMessage> pending = outbox.fetchUnpublished(batch);
            if (pending.isEmpty()) {
                return 0;
            }
            pending.forEach(stream::deliver);
            outbox.markPublished(pending.stream().map(OutboxMessage::id).toList());
            return pending.size();
        });
    }
}
