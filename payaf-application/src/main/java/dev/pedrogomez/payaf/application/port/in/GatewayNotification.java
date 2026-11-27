package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Notificación de una PSP ya verificada y traducida por el adaptador de webhooks.
 *
 * @param gateway          pasarela que la envía
 * @param notificationId   identificador de la notificación en la PSP (para descartar repetidas)
 * @param gatewayReference referencia de la operación en la PSP (la misma que dio al autorizar)
 * @param type             qué ha pasado
 * @param amount                 importe afectado; obligatorio en REFUND_CONFIRMED
 * @param gatewayRefundReference referencia del reembolso en la PSP; obligatoria en REFUND_CONFIRMED,
 *                               porque es lo que distingue un reembolso nuevo (hecho desde el
 *                               panel de la PSP) de la confirmación de uno que ya aplicamos
 * @param occurredAt             cuándo ocurrió según la PSP (informativo: el pago usa nuestro reloj)
 */
public record GatewayNotification(GatewayId gateway, String notificationId, String gatewayReference,
                                  NotificationType type, Optional<Money> amount,
                                  Optional<String> gatewayRefundReference, Instant occurredAt) {

    public GatewayNotification {
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(notificationId);
        Objects.requireNonNull(gatewayReference);
        Objects.requireNonNull(type);
        Objects.requireNonNull(amount, "el importe opcional no puede ser nulo: usa Optional.empty()");
        Objects.requireNonNull(gatewayRefundReference,
                "la referencia de reembolso opcional no puede ser nula: usa Optional.empty()");
        Objects.requireNonNull(occurredAt);
        if (notificationId.isBlank()) {
            throw new InvalidValueException("La notificación debe tener identificador");
        }
        if (gatewayReference.isBlank()) {
            throw new InvalidValueException("La notificación debe traer la referencia de la pasarela");
        }
        if (type == NotificationType.REFUND_CONFIRMED && amount.isEmpty()) {
            throw new InvalidValueException("Una notificación REFUND_CONFIRMED debe traer el importe");
        }
        if (type == NotificationType.REFUND_CONFIRMED && gatewayRefundReference.filter(r -> !r.isBlank()).isEmpty()) {
            throw new InvalidValueException("Una notificación REFUND_CONFIRMED debe traer la referencia del reembolso");
        }
    }
}
