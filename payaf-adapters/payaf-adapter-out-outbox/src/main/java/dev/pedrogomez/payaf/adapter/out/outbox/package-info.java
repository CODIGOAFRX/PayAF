/**
 * Adaptador de salida (driven) del patrón outbox.
 * <p>
 * Implementa el puerto de publicación de eventos: registra los eventos del dominio
 * junto con el cambio de estado del pago y los entrega después a sus destinatarios
 * (panel en tiempo real, notificaciones), garantizando que no se pierde ninguno.
 */
package dev.pedrogomez.payaf.adapter.out.outbox;
