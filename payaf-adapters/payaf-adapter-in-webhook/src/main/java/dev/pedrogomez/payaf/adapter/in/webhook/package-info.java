/**
 * Adaptador de entrada (driving) de webhooks.
 * <p>
 * Recibe las notificaciones asíncronas de los PSP (pago capturado, fallido,
 * reembolsado...), valida su firma y las convierte en llamadas a los puertos de
 * entrada para actualizar el estado de los pagos.
 */
package dev.pedrogomez.payaf.adapter.in.webhook;
