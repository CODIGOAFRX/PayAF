package dev.pedrogomez.payaf.application.port.out;

import java.util.Collection;
import java.util.List;

/**
 * Puerto de salida (driven): la otra mitad del patrón Outbox, la que lee lo que
 * {@link EventPublisher} dejó guardado para repartirlo.
 * <p>
 * Contrato: se llama dentro de una transacción abierta con {@link TransactionRunner}. Los mensajes
 * que devuelve {@link #fetchUnpublished} quedan reservados para esa transacción: otro relay que lea
 * a la vez (otra réplica) no los ve y salta a los siguientes (en PostgreSQL, con
 * {@code FOR UPDATE SKIP LOCKED}). Si la transacción se deshace sin {@link #markPublished}, los
 * mensajes vuelven a quedar pendientes: la entrega es <em>al menos una vez</em>, nunca «como mucho una».
 */
public interface OutboxReader {

    /** Los mensajes pendientes más antiguos, por orden de {@link OutboxMessage#id()}; como mucho {@code limit}. */
    List<OutboxMessage> fetchUnpublished(int limit);

    /** Marca como publicados esos mensajes. Los identificadores desconocidos se ignoran. */
    void markPublished(Collection<Long> ids);
}
