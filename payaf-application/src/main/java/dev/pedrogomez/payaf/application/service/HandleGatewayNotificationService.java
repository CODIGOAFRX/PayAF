package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.HandleGatewayNotificationUseCase;
import dev.pedrogomez.payaf.application.port.in.NotificationResult;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.ProcessedNotificationStore;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.RefundOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aplica las notificaciones asíncronas de las PSP. Todo ocurre en una sola transacción:
 * <ol>
 *   <li>Se marca la notificación como procesada; si ya lo estaba, DUPLICATE.</li>
 *   <li>Se busca el pago por la referencia de la pasarela; si no aparece, UNKNOWN_PAYMENT
 *       (la marca se queda: reintentar no hará aparecer el pago).</li>
 *   <li>Se aplica el tipo:
 *       <ul>
 *         <li>CAPTURE_CONFIRMED: si ya está capturado (o reembolsado), ALREADY_IN_STATE; si
 *             no, se captura.</li>
 *         <li>REFUND_CONFIRMED: si el pago ya tiene un reembolso con esa referencia (lo
 *             pedimos nosotros por la API y la PSP lo confirma ahora), ALREADY_IN_STATE y el
 *             total no cambia. Si no, es un reembolso hecho fuera de PayAF (desde el panel de
 *             la PSP) y se aplica.</li>
 *         <li>AUTHORIZATION_REVERSED: si está AUTHORIZED se anula; en cualquier otro estado,
 *             ALREADY_IN_STATE.</li>
 *       </ul></li>
 * </ol>
 * Si el dominio rechaza la operación (capturar un pago anulado, reembolsar más de lo que
 * queda), su excepción sale tal cual, la transacción se deshace con la marca incluida y la
 * PSP podrá reintentar: es una incoherencia que alguien tiene que mirar, no un duplicado.
 * <p>
 * El instante del cambio es nuestro reloj, no el {@code occurredAt} de la PSP: el agregado
 * exige tiempo monótono y no podemos fiarnos del reloj de un tercero.
 */
public final class HandleGatewayNotificationService implements HandleGatewayNotificationUseCase {

    private final PaymentRepository payments;
    private final ProcessedNotificationStore processedNotifications;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactions;
    private final Clock clock;

    public HandleGatewayNotificationService(PaymentRepository payments,
                                            ProcessedNotificationStore processedNotifications,
                                            EventPublisher eventPublisher, TransactionRunner transactions,
                                            Clock clock) {
        this.payments = Objects.requireNonNull(payments);
        this.processedNotifications = Objects.requireNonNull(processedNotifications);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public NotificationResult handle(GatewayNotification notification) {
        Objects.requireNonNull(notification, "la notificación no puede ser nula");
        return transactions.inTransaction(() -> {
            if (!processedNotifications.markProcessed(notification.gateway(), notification.notificationId())) {
                return NotificationResult.DUPLICATE;
            }
            Optional<Payment> found = payments.findByGatewayReference(notification.gateway(),
                    notification.gatewayReference());
            if (found.isEmpty()) {
                return NotificationResult.UNKNOWN_PAYMENT;
            }
            Payment payment = found.get();
            NotificationResult result = apply(notification, payment, ServiceSupport.nextInstant(clock, payment));
            if (result == NotificationResult.APPLIED) {
                payments.save(payment);
                eventPublisher.publish(payment.pullEvents());
            }
            return result;
        });
    }

    private static NotificationResult apply(GatewayNotification notification, Payment payment, Instant now) {
        return switch (notification.type()) {
            case CAPTURE_CONFIRMED -> {
                if (payment.status().isCaptured()) {
                    yield NotificationResult.ALREADY_IN_STATE;
                }
                payment.capture(now);
                yield NotificationResult.APPLIED;
            }
            case REFUND_CONFIRMED -> {
                // GatewayNotification garantiza que REFUND_CONFIRMED trae importe y referencia.
                RefundOutcome outcome = payment.refund(notification.amount().orElseThrow(),
                        notification.gatewayRefundReference().orElseThrow(), now);
                yield outcome == RefundOutcome.APPLIED
                        ? NotificationResult.APPLIED
                        : NotificationResult.ALREADY_IN_STATE;
            }
            case AUTHORIZATION_REVERSED -> {
                if (payment.status() != PaymentStatus.AUTHORIZED) {
                    yield NotificationResult.ALREADY_IN_STATE;
                }
                payment.cancel(now);
                yield NotificationResult.APPLIED;
            }
        };
    }
}
