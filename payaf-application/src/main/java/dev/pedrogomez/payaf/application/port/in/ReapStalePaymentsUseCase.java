package dev.pedrogomez.payaf.application.port.in;

import java.time.Duration;

/**
 * Puerto de entrada (driving): recoger los pagos huérfanos, los que se han quedado en PENDING
 * porque la petición que los enrutaba murió a medias (un reinicio, un {@code Error} de la JVM,
 * una autorización aprobada que no se pudo apuntar).
 * <p>
 * Lo invoca un planificador (en bootstrap), no un comercio. Por cada pago PENDING que no ha
 * cambiado desde hace más de {@code olderThan}:
 * <ul>
 *   <li>si alguna pasarela llegó a aprobarlo (hay un intento APPROVED con referencia), se
 *       autoriza con esa pasarela y esa referencia: el dinero está retenido en la PSP y el pago
 *       tiene que decirlo (<em>recuperado</em>);</li>
 *   <li>si no, falla con el motivo {@code ABANDONED} (<em>abandonado</em>).</li>
 * </ul>
 * En los dos casos se completa su clave de idempotencia, para que un reintento del comercio
 * reciba el resultado en lugar de IDEMPOTENCY_IN_PROGRESS para siempre.
 *
 * @see dev.pedrogomez.payaf.application.service.ReapStalePaymentsService
 */
public interface ReapStalePaymentsUseCase {

    /**
     * @param olderThan antigüedad mínima (desde la última actualización) para dar un PENDING por
     *                  huérfano; positiva
     * @return cuántos pagos se han dado por abandonados y cuántos se han recuperado
     */
    ReapReport reap(Duration olderThan);
}
