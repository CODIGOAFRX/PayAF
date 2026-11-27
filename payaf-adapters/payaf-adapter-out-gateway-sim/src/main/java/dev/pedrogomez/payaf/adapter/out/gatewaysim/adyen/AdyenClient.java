package dev.pedrogomez.payaf.adapter.out.gatewaysim.adyen;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.DeterministicDecline;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.References;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Imitación de la Checkout API de Adyen ({@code /payments} y las modificaciones
 * {@code /payments/{pspReference}/captures|refunds|cancels}). Es el «idioma» de Adyen;
 * {@link AdyenPaymentGateway} lo traduce al puerto.
 * <ul>
 *   <li>Toda operación recibe un {@code pspReference} de 16 dígitos, también las
 *       modificaciones, que tienen el suyo propio y apuntan al pago con
 *       {@code paymentPspReference}.</li>
 *   <li>{@code /payments} responde con {@code resultCode}: {@code Authorised}, {@code Refused}
 *       (con {@code refusalReason}, p. ej. {@code "Not enough balance"}, código 12, en la regla de
 *       los importes terminados en 51) o {@code Error}. La autorización es separada de la captura
 *       (captura manual).</li>
 *   <li>Las modificaciones aceptadas responden {@code status=received}.</li>
 *   <li>Las peticiones inválidas lanzan {@link AdyenServiceException} con HTTP 422 y un
 *       {@code errorCode} al estilo de Adyen (simulado): {@code 167} referencia original
 *       desconocida, {@code 168} la operación no está permitida en el estado actual (capturar dos
 *       veces), {@code 137} importe no válido y {@code 138} el reembolso supera lo capturado.</li>
 *   <li>Admite {@code Idempotency-Key} en {@code /payments}.</li>
 * </ul>
 * Es seguro para hilos.
 */
public final class AdyenClient {

    public static final String AUTHORISED = "Authorised";
    public static final String REFUSED = "Refused";
    public static final String ERROR = "Error";
    public static final String RECEIVED = "received";

    /** Importe al estilo de Adyen: divisa ISO en mayúsculas y {@code value} en unidades menores. */
    public record Amount(String currency, long value) {
    }

    /** Cuerpo de {@code POST /payments}. */
    public record PaymentRequest(Amount amount, String reference, String merchantAccount) {
    }

    /** Respuesta de {@code POST /payments}. */
    public record PaymentResponse(String pspReference, String resultCode, String refusalReason,
                                  String refusalReasonCode, String merchantReference, Amount amount) {
    }

    /** Respuesta de una modificación (captura, reembolso o anulación). */
    public record ModificationResponse(String pspReference, String paymentPspReference, String status,
                                       Amount amount) {
    }

    /** Error de la API ({@code status}, {@code errorCode}, {@code message}, {@code errorType}). */
    public static final class AdyenServiceException extends RuntimeException {

        private final int status;
        private final String errorCode;
        private final String errorType;

        public AdyenServiceException(int status, String errorCode, String message, String errorType) {
            super(message);
            this.status = status;
            this.errorCode = errorCode;
            this.errorType = errorType;
        }

        public int status() {
            return status;
        }

        public String errorCode() {
            return errorCode;
        }

        public String errorType() {
            return errorType;
        }
    }

    private final SimulatedLedger ledger = new SimulatedLedger();
    private final ConcurrentMap<String, PaymentResponse> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Random random;

    public AdyenClient(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /** {@code POST /payments} con captura manual. */
    public PaymentResponse payments(PaymentRequest request, String idempotencyKey) {
        if (request.amount().value() <= 0) {
            throw new AdyenServiceException(422, "137", "Invalid amount specified", "validation");
        }
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            String pspReference = newPspReference();
            if (DeterministicDecline.declines(request.amount().value())) {
                return new PaymentResponse(pspReference, REFUSED, "Not enough balance", "12", request.reference(),
                        request.amount());
            }
            ledger.open(pspReference, request.amount().value(), request.amount().currency());
            return new PaymentResponse(pspReference, AUTHORISED, null, null, request.reference(), request.amount());
        });
    }

    /** {@code POST /payments/{paymentPspReference}/captures}. */
    public ModificationResponse captures(String paymentPspReference, Amount amount) {
        onLedger(() -> ledger.capture(paymentPspReference, amount.value(), amount.currency()));
        return new ModificationResponse(newPspReference(), paymentPspReference, RECEIVED, amount);
    }

    /** {@code POST /payments/{paymentPspReference}/refunds}. */
    public ModificationResponse refunds(String paymentPspReference, Amount amount) {
        onLedger(() -> ledger.refund(paymentPspReference, amount.value(), amount.currency()));
        return new ModificationResponse(newPspReference(), paymentPspReference, RECEIVED, amount);
    }

    /** {@code POST /payments/{paymentPspReference}/cancels}. */
    public ModificationResponse cancels(String paymentPspReference) {
        SimulatedLedger.Entry entry = onLedger(() -> ledger.cancel(paymentPspReference));
        return new ModificationResponse(newPspReference(), paymentPspReference, RECEIVED,
                new Amount(entry.currency(), entry.amountMinor()));
    }

    public SimulatedLedger ledger() {
        return ledger;
    }

    private String newPspReference() {
        return References.digits(random, 16, true);
    }

    private static SimulatedLedger.Entry onLedger(java.util.function.Supplier<SimulatedLedger.Entry> operation) {
        try {
            return operation.get();
        } catch (SimulatedLedger.RejectedException e) {
            throw switch (e.rejection()) {
                case UNKNOWN_REFERENCE -> new AdyenServiceException(422, "167",
                        "Original pspReference required for this operation", "validation");
                case INVALID_STATE -> new AdyenServiceException(422, "168",
                        "Modification not allowed in the current state of the payment", "validation");
                case AMOUNT_MISMATCH -> new AdyenServiceException(422, "137", "Invalid amount specified",
                        "validation");
                case REFUND_EXCEEDS_CAPTURED -> new AdyenServiceException(422, "138",
                        "Refund amount exceeds the captured amount", "validation");
            };
        }
    }
}
