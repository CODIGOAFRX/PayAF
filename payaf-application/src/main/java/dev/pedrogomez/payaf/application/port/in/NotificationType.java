package dev.pedrogomez.payaf.application.port.in;

/** Tipos de notificación asíncrona de una PSP que PayAF sabe aplicar. */
public enum NotificationType {
    /** La PSP confirma que ha cobrado el pago. */
    CAPTURE_CONFIRMED,
    /** La PSP confirma un reembolso; trae el importe. */
    REFUND_CONFIRMED,
    /** La PSP ha anulado la autorización (por caducidad o a petición del emisor). */
    AUTHORIZATION_REVERSED
}
