package dev.pedrogomez.payaf.application.exception;

import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Objects;
import java.util.Optional;

/**
 * Error de un caso de uso que no es la violación de una regla del dominio, sino de la
 * orquestación: el pago no existe, la clave de idempotencia choca o la pasarela no ha
 * aprobado una operación.
 * <p>
 * Igual que {@link dev.pedrogomez.payaf.domain.shared.DomainException}, lleva un
 * {@link #code()} estable que los adaptadores de entrada traducen (a HTTP, a un código de
 * salida de la CLI) sin mirar mensajes. Las excepciones de dominio no se envuelven: pasan
 * tal cual, con su propio código.
 */
public class ApplicationException extends RuntimeException {

    /** No hay ningún pago con ese identificador. */
    public static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    /** La clave de idempotencia ya se usó con una orden de contenido distinto. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** La misma orden se está procesando ahora mismo en otra petición. */
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    /** La pasarela no ha aprobado una captura, reembolso o anulación; el pago no ha cambiado. */
    public static final String GATEWAY_OPERATION_FAILED = "GATEWAY_OPERATION_FAILED";
    /**
     * La PSP aprobó la autorización pero no se pudo apuntar en el pago: queda PENDING, con la
     * clave en curso, a la espera de reconciliación (fase 4). Hay dinero retenido en la PSP.
     */
    public static final String AUTHORIZATION_UNRECORDED = "AUTHORIZATION_UNRECORDED";
    /** Otra petición ha modificado el pago entre que se cargó y se guardó (ver {@link ConcurrentPaymentModificationException}). */
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    private final String code;

    /** Para las subclases que llevan datos propios, como {@link ConcurrentPaymentModificationException}. */
    protected ApplicationException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    /** Código estable y legible por máquinas. */
    public String code() {
        return code;
    }

    public static ApplicationException paymentNotFound(PaymentId id) {
        return new ApplicationException(PAYMENT_NOT_FOUND, "No existe el pago " + id);
    }

    public static ApplicationException idempotencyConflict(IdempotencyKey key) {
        return new ApplicationException(IDEMPOTENCY_CONFLICT,
                "La clave de idempotencia «" + key + "» ya se usó con una orden distinta");
    }

    public static ApplicationException idempotencyInProgress(IdempotencyKey key, PaymentId id) {
        return new ApplicationException(IDEMPOTENCY_IN_PROGRESS,
                "La orden con clave «" + key + "» se está procesando (pago " + id + "); reinténtalo más tarde");
    }

    public static ApplicationException authorizationUnrecorded(PaymentId id, GatewayId gateway,
                                                               String gatewayReference) {
        return new ApplicationException(AUTHORIZATION_UNRECORDED,
                "La pasarela " + gateway + " aprobó el pago " + id + " (referencia " + gatewayReference
                        + ") pero no se pudo registrar la autorización; queda PENDING para reconciliar");
    }

    public static ApplicationException gatewayOperationFailed(String operation, PaymentId id, GatewayId gateway,
                                                              AttemptOutcome outcome, Optional<String> gatewayCode) {
        return new ApplicationException(GATEWAY_OPERATION_FAILED,
                "La pasarela " + gateway + " no ha aprobado la operación " + operation + " del pago " + id
                        + ": " + outcome + gatewayCode.map(c -> " (" + c + ")").orElse(""));
    }
}
