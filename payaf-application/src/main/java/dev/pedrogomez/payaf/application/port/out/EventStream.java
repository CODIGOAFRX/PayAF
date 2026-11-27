package dev.pedrogomez.payaf.application.port.out;

/**
 * Puerto de salida (driven): adónde reparte el relay los mensajes del outbox (a un broadcaster en
 * proceso para el SSE del panel; en un sistema real, a un broker de mensajería).
 * <p>
 * Contrato: {@link #deliver} no se bloquea ni lanza excepciones por culpa de un suscriptor lento
 * o caído. El relay lo llama con la transacción del outbox abierta, así que tiene que ser rápido.
 */
public interface EventStream {

    void deliver(OutboxMessage message);
}
