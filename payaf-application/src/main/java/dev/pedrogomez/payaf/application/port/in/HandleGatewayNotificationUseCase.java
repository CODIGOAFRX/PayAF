package dev.pedrogomez.payaf.application.port.in;

/**
 * Puerto de entrada (driving): aplicar una notificación asíncrona (webhook) de una PSP.
 * <p>
 * <strong>La verificación de la firma NO es de este caso de uso.</strong> Le llega una
 * notificación ya verificada por el adaptador de webhooks: comprobar la firma depende del
 * formato de cada PSP (cabeceras, HMAC sobre el cuerpo en bruto, secretos), que es
 * infraestructura. Lo que sí es de la aplicación es qué significa la notificación para el
 * pago y garantizar que cada una se aplica una sola vez.
 */
public interface HandleGatewayNotificationUseCase {

    NotificationResult handle(GatewayNotification notification);
}
