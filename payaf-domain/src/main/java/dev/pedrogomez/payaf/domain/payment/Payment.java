package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import dev.pedrogomez.payaf.domain.shared.CurrencyMismatchException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agregado raíz del pago.
 * <p>
 * Es el corazón del dominio. Un agregado es la frontera de consistencia: todo lo que hay
 * dentro (estado, intentos, reembolsos) cambia junto y siempre cumple sus invariantes. Por
 * eso no hay setters: cada cambio pasa por un método de negocio ({@link #authorize},
 * {@link #capture}, {@link #refund}...) que comprueba en {@link PaymentStatus} que la
 * transición está permitida y, si no lo está, lanza {@link IllegalPaymentTransitionException}.
 * Ni el caso de uso ni el adaptador JPA pueden dejar un pago en un estado imposible.
 * <p>
 * Invariantes que protege:
 * <ul>
 *   <li>El importe del pago es positivo y no cambia nunca.</li>
 *   <li>Solo se registran intentos mientras el pago está PENDING.</li>
 *   <li>Un pago autorizado (y todo lo que viene después) recuerda qué pasarela lo autorizó.</li>
 *   <li>Se captura siempre el total: no hay capturas parciales.</li>
 *   <li>Lo reembolsado nunca supera lo capturado, y si lo iguala el pago pasa a REFUNDED.</li>
 * </ul>
 * Cada cambio deja un evento de dominio en una lista interna. El agregado no publica
 * nada: la aplicación los recoge con {@link #pullEvents()} después de guardar. Tampoco lee
 * el reloj: el instante llega siempre como parámetro, para que el dominio sea determinista
 * y se pueda probar con instantes fijos.
 * <p>
 * <strong>Tiempo monótono.</strong> Como el reloj llega de fuera, el agregado no se fía:
 * todo método de negocio rechaza con {@link NonMonotonicTimeException} un instante anterior
 * a {@link #updatedAt()} (uno igual sí vale). La historia del pago (eventos e intentos) es
 * su auditoría y tiene que leerse en orden, y un pago con {@code updatedAt} anterior a
 * {@code createdAt} ya no se podría rehidratar. La comprobación se hace la última, justo
 * antes de modificar nada, para que un error de negocio (transición ilegal, reembolso
 * excesivo) tenga prioridad sobre un reloj desajustado.
 * <p>
 * No es seguro para hilos; la aplicación se encarga de no modificar el mismo pago a la vez.
 */
public final class Payment {

    private final PaymentId id;
    private final PaymentRequest request;
    private final List<GatewayAttempt> attempts;
    private final Instant createdAt;
    private final List<DomainEvent> events = new ArrayList<>();

    private PaymentStatus status;
    private GatewayId authorizedBy;
    private String gatewayReference;
    private String failureReason;
    private Money refundedAmount;
    private Instant updatedAt;

    private Payment(PaymentId id, PaymentRequest request, PaymentStatus status, List<GatewayAttempt> attempts,
                    GatewayId authorizedBy, String gatewayReference, String failureReason, Money refundedAmount,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.request = request;
        this.status = status;
        this.attempts = new ArrayList<>(attempts);
        this.authorizedBy = authorizedBy;
        this.gatewayReference = gatewayReference;
        this.failureReason = failureReason;
        this.refundedAmount = refundedAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Crea un pago nuevo en PENDING y emite {@link PaymentCreated}. */
    public static Payment create(PaymentId id, PaymentRequest request, Instant now) {
        Objects.requireNonNull(id, "el identificador no puede ser nulo");
        Objects.requireNonNull(request, "la orden de pago no puede ser nula");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        Payment payment = new Payment(id, request, PaymentStatus.PENDING, List.of(), null, null, null,
                Money.zero(request.amount().currency()), now, now);
        payment.events.add(new PaymentCreated(id, request.amount(), request.country(),
                request.merchantReference(), now));
        return payment;
    }

    /**
     * Reconstruye un pago desde persistencia, sin emitir eventos: esos hechos ya ocurrieron
     * y se publicaron en su día. Es la puerta que usará el adaptador JPA.
     * <p>
     * Aunque los datos vengan de nuestra propia base de datos, se validan los invariantes:
     * el agregado no se fía de lo que le dan, y un pago corrupto debe fallar al cargarse,
     * no tres pasos después.
     *
     * @param authorizedBy     pasarela que lo autorizó, o {@code null} si no se ha autorizado
     * @param gatewayReference referencia en la pasarela, o {@code null}
     * @param failureReason    motivo del fallo, o {@code null} si no ha fallado
     * @throws InconsistentPaymentStateException si la combinación de datos es imposible
     */
    public static Payment rehydrate(PaymentId id, PaymentRequest request, PaymentStatus status,
                                    List<GatewayAttempt> attempts, GatewayId authorizedBy,
                                    String gatewayReference, String failureReason, Money refundedAmount,
                                    Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(id, "el identificador no puede ser nulo");
        Objects.requireNonNull(request, "la orden de pago no puede ser nula");
        Objects.requireNonNull(status, "el estado no puede ser nulo");
        Objects.requireNonNull(attempts, "la lista de intentos no puede ser nula");
        Objects.requireNonNull(refundedAmount, "el importe reembolsado no puede ser nulo");
        Objects.requireNonNull(createdAt, "la fecha de creación no puede ser nula");
        Objects.requireNonNull(updatedAt, "la fecha de actualización no puede ser nula");
        if (attempts.stream().anyMatch(Objects::isNull)) {
            throw inconsistent(id, "hay intentos nulos");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw inconsistent(id, "la fecha de actualización es anterior a la de creación");
        }

        Money amount = request.amount();
        if (refundedAmount.currency() != amount.currency()) {
            throw inconsistent(id, "lo reembolsado está en " + refundedAmount.currency()
                    + " y el pago en " + amount.currency());
        }
        if (refundedAmount.amountMinor() < 0 || refundedAmount.isGreaterThan(amount)) {
            throw inconsistent(id, "lo reembolsado (" + refundedAmount + ") está fuera de [0, " + amount + "]");
        }
        boolean refundCoherent = switch (status) {
            case PARTIALLY_REFUNDED -> refundedAmount.isPositive() && amount.isGreaterThan(refundedAmount);
            case REFUNDED -> refundedAmount.equals(amount);
            default -> refundedAmount.isZero();
        };
        if (!refundCoherent) {
            throw inconsistent(id, "el importe reembolsado " + refundedAmount + " no casa con el estado " + status);
        }

        boolean mustBeAuthorized = status != PaymentStatus.PENDING && status != PaymentStatus.FAILED;
        if (mustBeAuthorized != (authorizedBy != null)) {
            throw inconsistent(id, mustBeAuthorized
                    ? "en estado " + status + " debe constar la pasarela que lo autorizó"
                    : "en estado " + status + " no puede constar una pasarela autorizadora");
        }
        if (mustBeAuthorized != (gatewayReference != null)) {
            throw inconsistent(id, "la referencia de la pasarela no casa con el estado " + status);
        }
        if (gatewayReference != null && !hasText(gatewayReference)) {
            throw inconsistent(id, "la referencia de la pasarela está en blanco");
        }
        if ((status == PaymentStatus.FAILED) != (failureReason != null)) {
            throw inconsistent(id, "el motivo de fallo solo existe, y es obligatorio, en FAILED");
        }
        if (failureReason != null && !hasText(failureReason)) {
            throw inconsistent(id, "el motivo de fallo está en blanco");
        }
        return new Payment(id, request, status, attempts, authorizedBy, gatewayReference, failureReason,
                refundedAmount, createdAt, updatedAt);
    }

    private static InconsistentPaymentStateException inconsistent(PaymentId id, String detail) {
        return new InconsistentPaymentStateException("No se puede reconstruir el pago " + id + ": " + detail);
    }

    /**
     * Regla común para los textos libres del pago (referencia de la pasarela y motivo del
     * fallo): tienen que tener algo más que espacios. La usan los métodos de negocio, que
     * rechazan la entrada, y {@link #rehydrate}, que rechaza lo persistido; así lo que no se
     * puede crear tampoco se puede cargar.
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ---------------------------------------------------------------------------------
    // Métodos de negocio
    // ---------------------------------------------------------------------------------

    /**
     * Anota un intento de autorización en una pasarela. Solo se admite en PENDING: una vez
     * autorizado o fallido, el pago ya no se está enrutando.
     * <p>
     * Registrar el intento no cambia el estado: ni siquiera uno APPROVED autoriza el pago.
     * La autorización es una decisión explícita ({@link #authorize}) que toma el caso de uso.
     */
    public void recordAttempt(GatewayAttempt attempt) {
        Objects.requireNonNull(attempt, "el intento no puede ser nulo");
        if (status != PaymentStatus.PENDING) {
            throw new IllegalPaymentTransitionException(id, status, status);
        }
        requireMonotonic(attempt.at());
        attempts.add(attempt);
        updatedAt = attempt.at();
        events.add(new GatewayAttemptRecorded(id, attempt, attempt.at()));
    }

    /** PENDING → AUTHORIZED. Recuerda qué pasarela lo autorizó y con qué referencia. */
    public void authorize(GatewayId gateway, String gatewayReference, Instant now) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(gatewayReference, "la referencia de la pasarela no puede ser nula");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        if (!hasText(gatewayReference)) {
            throw new InvalidValueException("La referencia de la pasarela no puede estar en blanco");
        }
        transitionTo(PaymentStatus.AUTHORIZED, now);
        this.authorizedBy = gateway;
        this.gatewayReference = gatewayReference;
        events.add(new PaymentAuthorized(id, gateway, gatewayReference, now));
    }

    /** PENDING → FAILED: ninguna pasarela lo ha autorizado. */
    public void fail(String reason, Instant now) {
        Objects.requireNonNull(reason, "el motivo no puede ser nulo");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        if (!hasText(reason)) {
            throw new InvalidValueException("El motivo del fallo no puede estar en blanco");
        }
        transitionTo(PaymentStatus.FAILED, now);
        this.failureReason = reason;
        events.add(new PaymentFailed(id, reason, now));
    }

    /** AUTHORIZED → CAPTURED, siempre por el total: el negocio no contempla capturas parciales. */
    public void capture(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        transitionTo(PaymentStatus.CAPTURED, now);
        events.add(new PaymentCaptured(id, request.amount(), now));
    }

    /** AUTHORIZED → CANCELED: se libera la autorización sin haber cobrado. */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        transitionTo(PaymentStatus.CANCELED, now);
        events.add(new PaymentCanceled(id, now));
    }

    /**
     * Devuelve parte o la totalidad de lo capturado. Desde CAPTURED o PARTIALLY_REFUNDED pasa
     * a PARTIALLY_REFUNDED si aún queda algo por devolver, o a REFUNDED si con este
     * reembolso se devuelve todo.
     * <p>
     * Orden de las comprobaciones: primero el propio importe (divisa y signo), después que el
     * estado admita reembolsos, que no se devuelva más de lo que queda por reembolsar y, por
     * último, que el instante no sea anterior a la última actualización. El exceso se compara
     * con {@link #remainingRefundable()} antes de sumar nada, así un importe enorme da
     * {@link RefundExceedsCapturedException} y no un desbordamiento.
     *
     * @throws CurrencyMismatchException         si el importe no está en la divisa del pago
     * @throws InvalidValueException             si el importe no es positivo
     * @throws IllegalPaymentTransitionException si el pago no está capturado
     * @throws RefundExceedsCapturedException    si el total reembolsado superaría lo capturado
     * @throws NonMonotonicTimeException         si {@code now} es anterior a la última actualización
     */
    public void refund(Money amount, Instant now) {
        Objects.requireNonNull(amount, "el importe no puede ser nulo");
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        if (amount.currency() != request.amount().currency()) {
            throw new CurrencyMismatchException(request.amount().currency(), amount.currency());
        }
        if (!amount.isPositive()) {
            throw new InvalidValueException("El importe del reembolso debe ser mayor que cero: " + amount);
        }
        // Lo que quedaría por devolver si el pago estuviera capturado. En los estados que admiten
        // reembolsos coincide con remainingRefundable(); en los demás sirve para nombrar bien el
        // destino que se intentaba (REFUNDED si se pide el total) en la transición ilegal.
        Money remaining = request.amount().minus(refundedAmount);
        PaymentStatus target = amount.equals(remaining) ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        requireTransition(target);
        if (amount.isGreaterThan(remaining)) {
            throw new RefundExceedsCapturedException(id, amount, remaining);
        }
        // Ya sabemos que amount <= capturado - reembolsado, así que la suma no puede desbordar.
        Money newTotal = refundedAmount.plus(amount);
        transitionTo(target, now);
        this.refundedAmount = newTotal;
        events.add(new PaymentRefunded(id, amount, newTotal, now));
    }

    private void requireTransition(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(id, status, target);
        }
    }

    private void transitionTo(PaymentStatus target, Instant now) {
        requireTransition(target);
        requireMonotonic(now);
        this.status = target;
        this.updatedAt = now;
    }

    private void requireMonotonic(Instant now) {
        if (now.isBefore(updatedAt)) {
            throw new NonMonotonicTimeException(id, updatedAt, now);
        }
    }

    /**
     * Entrega los eventos acumulados desde la última llamada y vacía la lista. Así cada
     * evento se publica una sola vez aunque el agregado siga vivo.
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    // ---------------------------------------------------------------------------------
    // Lectura
    // ---------------------------------------------------------------------------------

    public PaymentId id() {
        return id;
    }

    public PaymentRequest request() {
        return request;
    }

    public Money amount() {
        return request.amount();
    }

    public PaymentStatus status() {
        return status;
    }

    /** Intentos en el orden en que se registraron. La lista no se puede modificar. */
    public List<GatewayAttempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    public Optional<GatewayId> authorizedBy() {
        return Optional.ofNullable(authorizedBy);
    }

    public Optional<String> gatewayReference() {
        return Optional.ofNullable(gatewayReference);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /** Lo capturado: el total del pago si se ha capturado y cero en cualquier otro caso. */
    public Money capturedAmount() {
        return status.isCaptured() ? request.amount() : Money.zero(request.amount().currency());
    }

    public Money refundedAmount() {
        return refundedAmount;
    }

    /** Lo que aún se puede reembolsar: lo capturado menos lo ya reembolsado. Cero si no se ha capturado. */
    public Money remainingRefundable() {
        return capturedAmount().minus(refundedAmount);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Dos pagos son el mismo si tienen la misma identidad, como corresponde a una entidad. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Payment that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Payment[" + id + ", " + status + ", " + request.amount() + "]";
    }
}
