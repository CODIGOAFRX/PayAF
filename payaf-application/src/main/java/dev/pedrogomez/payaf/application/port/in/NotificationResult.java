package dev.pedrogomez.payaf.application.port.in;

/**
 * Qué se hizo con una notificación. Todos son respuestas correctas para la PSP (el
 * adaptador contestará 2xx), porque ninguno se arregla reintentando.
 */
public enum NotificationResult {
    /** Se ha aplicado y el pago ha cambiado de estado. */
    APPLIED,
    /** Ya se había procesado esta misma notificación. */
    DUPLICATE,
    /** El pago ya estaba en el estado que anuncia la notificación (p. ej. lo capturamos nosotros). */
    ALREADY_IN_STATE,
    /** No hay ningún pago con esa referencia de pasarela. */
    UNKNOWN_PAYMENT
}
