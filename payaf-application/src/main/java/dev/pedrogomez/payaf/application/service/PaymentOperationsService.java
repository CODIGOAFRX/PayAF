package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.CancelPaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Casos de uso de captura, reembolso y anulación. Los tres siguen el mismo guion, así que
 * comparten implementación:
 * <ol>
 *   <li>Cargar el pago (si no existe, PAYMENT_NOT_FOUND).</li>
 *   <li><strong>Ensayo previo con el dominio:</strong> se aplica la operación a una copia
 *       del pago. Si el dominio la rechaza (capturar un pago fallido, reembolsar de más), su
 *       {@code DomainException} sale tal cual y no se llama a la PSP en balde. Se ensaya
 *       sobre una copia, y no con comprobaciones propias, para no duplicar las reglas del
 *       agregado.</li>
 *   <li>Llamar a la pasarela que autorizó el pago, <strong>sin failover</strong>: la
 *       autorización (el dinero reservado) solo existe en esa PSP y ninguna otra puede
 *       capturarla, reembolsarla ni anularla. La llamada va sin transacción abierta.</li>
 *   <li>Registrar el resultado en su breaker, que cuenta igual que en la autorización.</li>
 *   <li>Si sale APPROVED, aplicar la operación al pago, guardarlo y publicar sus eventos en
 *       una transacción. Si no, GATEWAY_OPERATION_FAILED y el pago no cambia.</li>
 * </ol>
 * El breaker se consulta solo para registrar, no para cortar la llamada: aquí no hay otra
 * pasarela a la que ir, y la operación la ha pedido el comercio de forma explícita.
 */
public final class PaymentOperationsService implements CapturePaymentUseCase, RefundPaymentUseCase,
        CancelPaymentUseCase {

    /** Código del fallo cuando la pasarela que autorizó el pago ya no tiene adaptador. */
    static final String GATEWAY_NOT_CONFIGURED = "GATEWAY_NOT_CONFIGURED";

    private final PaymentRepository payments;
    private final GatewayCatalog catalog;
    private final CircuitBreakerStore breakerStore;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactions;
    private final Clock clock;

    public PaymentOperationsService(PaymentRepository payments, GatewayCatalog catalog,
                                    CircuitBreakerStore breakerStore, EventPublisher eventPublisher,
                                    TransactionRunner transactions, Clock clock) {
        this.payments = Objects.requireNonNull(payments);
        this.catalog = Objects.requireNonNull(catalog);
        this.breakerStore = Objects.requireNonNull(breakerStore);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Prefijo de la referencia inventada con la que se ensaya un reembolso antes de llamar a la PSP. */
    private static final String DRY_RUN_REFUND_PREFIX = "dry-run-";

    /** Lo que se aplica al pago cuando la PSP ha aprobado; recibe su respuesta. */
    private interface ApprovedOperation {
        void apply(Payment payment, GatewayResponse response, Instant now);
    }

    @Override
    public PaymentView capture(PaymentId paymentId) {
        return execute(paymentId, "capture", Payment::capture,
                (payment, response, now) -> payment.capture(now),
                (gateway, payment) -> gateway.capture(reference(payment), payment.amount()));
    }

    /**
     * Reembolso. El ensayo usa una referencia inventada y única (la de verdad no existe hasta
     * que responde la PSP), así el dominio aplica todas sus reglas como a un reembolso nuevo.
     * Al aplicarlo se usa la referencia del reembolso que devuelve la PSP; si la notificación
     * de ese reembolso llega después, el dominio la reconoce y no lo cuenta dos veces.
     */
    @Override
    public PaymentView refund(PaymentId paymentId, Money amount) {
        Objects.requireNonNull(amount, "el importe no puede ser nulo");
        return execute(paymentId, "refund",
                (payment, now) -> payment.refund(amount, DRY_RUN_REFUND_PREFIX + UUID.randomUUID(), now),
                (payment, response, now) -> payment.refund(amount, response.gatewayReference().orElseThrow(), now),
                (gateway, payment) -> gateway.refund(reference(payment), amount));
    }

    @Override
    public PaymentView cancel(PaymentId paymentId) {
        return execute(paymentId, "cancel", Payment::cancel,
                (payment, response, now) -> payment.cancel(now),
                (gateway, payment) -> gateway.cancel(reference(payment)));
    }

    private PaymentView execute(PaymentId paymentId, String operation, BiConsumer<Payment, Instant> dryRun,
                                ApprovedOperation domainOperation,
                                BiFunction<PaymentGateway, Payment, GatewayResponse> gatewayCall) {
        Objects.requireNonNull(paymentId, "el identificador no puede ser nulo");
        Payment payment = transactions.inTransaction(() -> payments.findById(paymentId))
                .orElseThrow(() -> ApplicationException.paymentNotFound(paymentId));

        // Ensayo: si el dominio no lo permite, la excepción sale aquí, antes de llamar a la PSP.
        dryRun.accept(copyOf(payment), ServiceSupport.nextInstant(clock, payment));

        // Si el ensayo ha pasado, el pago está autorizado y recuerda su pasarela.
        GatewayId gatewayId = payment.authorizedBy().orElseThrow();
        Optional<PaymentGateway> gateway = catalog.gateway(gatewayId);
        if (gateway.isEmpty()) {
            throw ApplicationException.gatewayOperationFailed(operation, paymentId, gatewayId, AttemptOutcome.ERROR,
                    Optional.of(GATEWAY_NOT_CONFIGURED));
        }

        GatewayResponse response = ServiceSupport.callSafely(clock, () -> gatewayCall.apply(gateway.get(), payment));
        CircuitBreaker breaker = breakerStore.get(gatewayId);
        ServiceSupport.recordAndSave(breakerStore, breaker, response.outcome(), clock.instant());

        if (!response.isApproved()) {
            // El pago no cambia, pero el breaker sí puede haber cambiado (abrirse): se publica.
            List<DomainEvent> breakerEvents = ServiceSupport.pullEvents(List.of(breaker));
            if (!breakerEvents.isEmpty()) {
                transactions.inTransaction(() -> {
                    eventPublisher.publish(breakerEvents);
                    return null;
                });
            }
            throw ApplicationException.gatewayOperationFailed(operation, paymentId, gatewayId, response.outcome(),
                    response.code());
        }

        return transactions.inTransaction(() -> {
            domainOperation.apply(payment, response, ServiceSupport.nextInstant(clock, payment));
            payments.save(payment);
            List<DomainEvent> events = new ArrayList<>(payment.pullEvents());
            events.addAll(ServiceSupport.pullEvents(List.of(breaker)));
            eventPublisher.publish(events);
            return PaymentView.from(payment);
        });
    }

    private static String reference(Payment payment) {
        return payment.gatewayReference().orElseThrow();
    }

    /**
     * Copia desechable del pago para el ensayo. Se reconstruye con {@link Payment#rehydrate},
     * así que no arrastra eventos ni comparte estado con el original.
     */
    private static Payment copyOf(Payment payment) {
        return Payment.rehydrate(payment.id(), payment.request(), payment.status(), payment.attempts(),
                payment.authorizedBy().orElse(null), payment.gatewayReference().orElse(null),
                payment.failureReason().orElse(null), payment.refundedAmount(), payment.refunds(),
                payment.createdAt(), payment.updatedAt());
    }
}
