/**
 * Adaptador de salida (driven) del reparto de eventos del outbox.
 * <p>
 * La escritura del outbox (en la misma transacción que el pago) la hace el adaptador de
 * persistencia de cada perfil; este módulo es el otro extremo: implementa el puerto
 * {@code EventStream}, al que el relay entrega los mensajes, con un broadcaster en proceso para
 * el SSE del panel. La REST se suscribe a través de bootstrap, sin importar este módulo.
 */
package dev.pedrogomez.payaf.adapter.out.outbox;
