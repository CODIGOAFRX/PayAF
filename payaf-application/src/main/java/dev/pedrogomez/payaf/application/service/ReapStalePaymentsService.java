package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.in.ReapStalePaymentsUseCase;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reaper de pagos huérfanos (ver {@link ReapStalePaymentsUseCase}).
 * <p>
 * <strong>Algoritmo.</strong> Se calcula el corte ({@code ahora - olderThan}) y se leen, en una
 * transacción, como mucho {@link #batchSize} pagos PENDING sin cambios desde antes del corte,
 * del más antiguo al más reciente. Después, <strong>una transacción por pago</strong>:
 * <ol>
 *   <li>Se vuelve a cargar el pago y se comprueba que sigue PENDING y por debajo del corte:
 *       si la petición original lo ha cerrado entretanto, no hay nada que hacer.</li>
 *   <li>Si tiene un intento APPROVED con referencia (el último, si hubiera varios), se autoriza
 *       con esa pasarela y esa referencia: es el caso AUTHORIZATION_UNRECORDED, en el que la
 *       PSP retiene el dinero y el pago no llegó a apuntarlo. Si no, falla con
 *       {@value #ABANDONED}.</li>
 *   <li>Se guarda, se publican sus eventos y se completa su clave de idempotencia
 *       ({@code payment.request().key()}: la orden guarda la clave con la que llegó, así que no
 *       hace falta un índice inverso de pago a clave en el almacén).</li>
 * </ol>
 * <p>
 * <strong>Concurrencia.</strong> La petición original puede estar viva todavía (una PSP muy
 * lenta). Lo resuelve la versión optimista del pago: quien guarde primero gana. Si gana el
 * reaper, el {@code save} final de la petición lanza CONCURRENT_MODIFICATION y lo decidido aquí
 * se respeta (ver {@link AuthorizePaymentService}). Si gana la petición, el {@code save} del
 * reaper lanza CONCURRENT_MODIFICATION, se deshace su transacción (nada de eventos ni de clave)
 * y ese pago se salta sin abortar el resto.
 * <p>
 * <strong>Otros fallos.</strong> Cualquier otra excepción al tratar un pago (el dominio lo
 * rechaza, la clave no está reservada para él...) también lo salta: su transacción se deshace,
 * se anota en el {@link System.Logger} de la JVM y se sigue con los demás. Un pago venenoso no
 * puede bloquear la limpieza de todos los que vienen detrás. No cuenta en el informe y se
 * volverá a intentar en la pasada siguiente.
 * <p>
 * <strong>Límite.</strong> Una pasada trata como mucho {@code batchSize} pagos; el resto queda
 * para la siguiente. Así una pasada nunca se alarga sin control y un pago que siempre falla no
 * provoca un bucle.
 * <p>
 * <strong>Hueco conocido.</strong> Si la PSP aprobó pero ni siquiera se llegó a registrar el
 * intento (la JVM murió justo después de la llamada), el pago no lleva rastro de la
 * aprobación y se da por abandonado aunque haya dinero retenido. Cerrarlo exige preguntar a la
 * PSP (reconciliación), que queda fuera de este caso de uso.
 */
public final class ReapStalePaymentsService implements ReapStalePaymentsUseCase {

    /** Motivo de fallo de un pago PENDING que nadie ha terminado de enrutar. */
    public static final String ABANDONED = "ABANDONED";

    /** Pagos que se tratan como mucho en una pasada si no se indica otro límite. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    private static final System.Logger LOG = System.getLogger(ReapStalePaymentsService.class.getName());

    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final int batchSize;

    public ReapStalePaymentsService(PaymentRepository payments, IdempotencyStore idempotency,
                                    EventPublisher eventPublisher, TransactionRunner transactions, Clock clock) {
        this(payments, idempotency, eventPublisher, transactions, clock, DEFAULT_BATCH_SIZE);
    }

    public ReapStalePaymentsService(PaymentRepository payments, IdempotencyStore idempotency,
                                    EventPublisher eventPublisher, TransactionRunner transactions, Clock clock,
                                    int batchSize) {
        this.payments = Objects.requireNonNull(payments);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1) {
            throw new IllegalArgumentException("El tamaño del lote debe ser al menos 1: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** Qué se ha hecho con un pago. */
    private enum Outcome { ABANDONED, RECOVERED, SKIPPED }

    @Override
    public ReapReport reap(Duration olderThan) {
        Objects.requireNonNull(olderThan, "la antigüedad no puede ser nula");
        if (olderThan.isNegative() || olderThan.isZero()) {
            throw new IllegalArgumentException("La antigüedad debe ser positiva: " + olderThan);
        }
        Instant cutoff = clock.instant().minus(olderThan);
        List<PaymentId> stale = transactions.inTransaction(
                () -> payments.findPendingOlderThan(cutoff, batchSize).stream().map(Payment::id).toList());

        int abandoned = 0;
        int recovered = 0;
        for (PaymentId id : stale) {
            switch (reapOne(id, cutoff)) {
                case ABANDONED -> abandoned++;
                case RECOVERED -> recovered++;
                case SKIPPED -> {
                }
            }
        }
        return new ReapReport(abandoned, recovered);
    }

    private Outcome reapOne(PaymentId id, Instant cutoff) {
        try {
            return transactions.inTransaction(() -> {
                Optional<Payment> found = payments.findById(id);
                if (found.isEmpty()) {
                    return Outcome.SKIPPED;
                }
                Payment payment = found.get();
                if (payment.status() != PaymentStatus.PENDING || !payment.updatedAt().isBefore(cutoff)) {
                    // La petición original lo ha cerrado (o ha avanzado) desde la lectura del lote.
                    return Outcome.SKIPPED;
                }
                Instant now = ServiceSupport.nextInstant(clock, payment);
                Optional<GatewayAttempt> approved = lastApprovedAttempt(payment);
                Outcome outcome;
                if (approved.isPresent()) {
                    payment.authorize(approved.get().gateway(), approved.get().gatewayReference().orElseThrow(), now);
                    outcome = Outcome.RECOVERED;
                } else {
                    payment.fail(ABANDONED, now);
                    outcome = Outcome.ABANDONED;
                }
                payments.save(payment);
                eventPublisher.publish(payment.pullEvents());
                idempotency.complete(payment.request().key(), payment.id());
                return outcome;
            });
        } catch (ConcurrentPaymentModificationException e) {
            // Otro proceso (normalmente la petición original) lo ha guardado antes: manda lo suyo.
            return Outcome.SKIPPED;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "El reaper no ha podido resolver el pago " + id
                    + "; se reintentará en la próxima pasada", e);
            return Outcome.SKIPPED;
        }
    }

    private static Optional<GatewayAttempt> lastApprovedAttempt(Payment payment) {
        List<GatewayAttempt> attempts = payment.attempts();
        for (int i = attempts.size() - 1; i >= 0; i--) {
            GatewayAttempt attempt = attempts.get(i);
            if (attempt.outcome() == AttemptOutcome.APPROVED
                    && attempt.gatewayReference().filter(r -> !r.isBlank()).isPresent()) {
                return Optional.of(attempt);
            }
        }
        return Optional.empty();
    }
}
