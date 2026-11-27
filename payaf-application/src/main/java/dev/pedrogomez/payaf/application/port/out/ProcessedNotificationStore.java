package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.shared.GatewayId;

/**
 * Puerto de salida (driven): qué notificaciones de las PSP ya se han procesado.
 * <p>
 * Las PSP reintentan sus webhooks hasta recibir un 2xx, así que la misma notificación
 * puede llegar varias veces. Este registro permite procesar cada una exactamente una vez.
 * <p>
 * Contrato: {@link #markProcessed} es atómico (marcar-si-no-estaba) y se llama dentro de la
 * transacción que aplica la notificación, para que, si esa transacción se deshace, la marca
 * se deshaga con ella y la PSP pueda reintentar.
 */
public interface ProcessedNotificationStore {

    /** Marca la notificación como procesada. Devuelve {@code false} si ya lo estaba. */
    boolean markProcessed(GatewayId gateway, String notificationId);
}
