package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
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
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.RefundOutcome;
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
import java.util.function.Predicate;

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
 * <p>
 * <strong>Concurrencia.</strong> El pago se carga en una transacción y se guarda en otra, con
 * la llamada a la PSP en medio. Si otra petición lo guarda entretanto (dos capturas a la vez,
 * una captura y una anulación), el {@code save} final lanza
 * {@link dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException}
 * (CONCURRENT_MODIFICATION) gracias a la versión optimista del pago. No se puede suponer que la
 * PSP rechace la segunda llamada: dos reembolsos parciales compatibles (30 + 30 de 100) los
 * aprueba los dos. Por eso <strong>un APPROVED de la PSP nunca se pierde por un
 * CONCURRENT_MODIFICATION</strong>: se recarga el pago y se vuelve a aplicar la operación con los
 * datos de la PSP, hasta {@value #MAX_RECORD_ATTEMPTS} veces. Un reembolso es seguro de
 * reaplicar porque el dominio es idempotente por la referencia del reembolso; una captura o una
 * anulación que la otra petición ya ha dejado hecha se da por buena y se devuelve el pago tal
 * como está; si el dominio la rechaza por otra razón, sale su {@code DomainException}. Si se
 * agotan los intentos, OPERATION_UNRECORDED con la referencia de la PSP para conciliarla. Un
 * CONCURRENT_MODIFICATION antes de llamar a la PSP no puede darse: solo se guarda después.
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

    /** Intentos de apuntar en el pago una operación aprobada por la PSP (ver {@code recordApproved}). */
    static final int MAX_RECORD_ATTEMPTS = 5;

    /**
     * Lo que se aplica al pago cuando la PSP ha aprobado; recibe su respuesta. Devuelve si el pago
     * ha cambiado (un reembolso ya apuntado no lo cambia).
     */
    private interface ApprovedOperation {
        boolean apply(Payment payment, GatewayResponse response, Instant now);
    }

    @Override
    public PaymentView capture(PaymentId paymentId) {
        return execute(paymentId, "capture", Payment::capture,
                (payment, response, now) -> {
                    payment.capture(now);
                    return true;
                },
                payment -> payment.status().isCaptured(),
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
                (payment, response, now) -> payment.refund(amount, response.gatewayReference().orElseThrow(), now)
                        == RefundOutcome.APPLIED,
                // Sin «ya aplicado» propio: el dominio es idempotente por la referencia del reembolso.
                payment -> false,
                (gateway, payment) -> gateway.refund(reference(payment), amount));
    }

    @Override
    public PaymentView cancel(PaymentId paymentId) {
        return execute(paymentId, "cancel", Payment::cancel,
                (payment, response, now) -> {
                    payment.cancel(now);
                    return true;
                },
                payment -> payment.status() == PaymentStatus.CANCELED,
                (gateway, payment) -> gateway.cancel(reference(payment)));
    }

    private PaymentView execute(PaymentId paymentId, String operation, BiConsumer<Payment, Instant> dryRun,
                                ApprovedOperation domainOperation, Predicate<Payment> alreadyApplied,
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

        return recordApproved(operation, payment, gatewayId, response, breaker, domainOperation, alreadyApplied);
    }

    /**
     * Apunta en el pago una operación que la PSP ya ha aprobado. <strong>Un APPROVED nunca se
     * pierde por un CONCURRENT_MODIFICATION:</strong> si el {@code save} choca con otra petición
     * que ha guardado el pago entretanto, se recarga el pago, se vuelve a aplicar la operación con
     * los datos de la PSP y se guarda otra vez, hasta {@value #MAX_RECORD_ATTEMPTS} intentos.
     * <ul>
     *   <li>Si el pago recargado ya está en el estado destino ({@code alreadyApplied}: capturado o
     *       anulado por la petición concurrente), se devuelve la vista actual sin error.</li>
     *   <li>Si la operación no cambia nada (un reembolso cuya referencia de la PSP ya está
     *       apuntada), tampoco se guarda.</li>
     *   <li>Si el dominio la rechaza por otra razón (capturar un pago que otra petición ha
     *       anulado), su {@code DomainException} sale tal cual.</li>
     *   <li>Si se agotan los intentos, OPERATION_UNRECORDED con la referencia de la PSP en el
     *       mensaje, para conciliarla después: la operación está hecha en la PSP.</li>
     * </ul>
     * Los eventos del breaker se sacan después del {@code save}, así que un intento que choca no
     * los pierde.
     */
    private PaymentView recordApproved(String operation, Payment loaded, GatewayId gatewayId, GatewayResponse response,
                                       CircuitBreaker breaker, ApprovedOperation domainOperation,
                                       Predicate<Payment> alreadyApplied) {
        PaymentId paymentId = loaded.id();
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            boolean reload = attempt > 1;
            try {
                return transactions.inTransaction(() -> {
                    Payment payment = reload
                            ? payments.findById(paymentId).orElseThrow(() -> ApplicationException.paymentNotFound(paymentId))
                            : loaded;
                    boolean changed = !alreadyApplied.test(payment)
                            && domainOperation.apply(payment, response, ServiceSupport.nextInstant(clock, payment));
                    if (changed) {
                        payments.save(payment);
                    }
                    List<DomainEvent> events = new ArrayList<>(payment.pullEvents());
                    events.addAll(ServiceSupport.pullEvents(List.of(breaker)));
                    eventPublisher.publish(events);
                    return PaymentView.from(payment);
                });
            } catch (ConcurrentPaymentModificationException e) {
                // Otra petición ha guardado el pago: se recarga y se vuelve a aplicar.
            }
        }
        throw ApplicationException.operationUnrecorded(operation, paymentId, gatewayId,
                response.gatewayReference().orElse("?"), MAX_RECORD_ATTEMPTS);
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
                payment.createdAt(), payment.updatedAt(), payment.version());
    }
}
