package dev.pedrogomez.payaf.adapter.out.gatewaysim.stripe;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.DeterministicDecline;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.References;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Imitación de la API de PaymentIntents de Stripe, con sus records, sus estados y sus errores.
 * Es el «idioma» de Stripe; {@link StripePaymentGateway} lo traduce al puerto.
 * <ul>
 *   <li>Un PaymentIntent se identifica con {@code pi_} + 24 alfanuméricos y un reembolso con
 *       {@code re_} + 24.</li>
 *   <li>Se crea con {@code capture_method=manual} y {@code confirm=true}: si el emisor lo
 *       autoriza queda en {@code requires_capture}; si lo rechaza, en
 *       {@code requires_payment_method} con {@code last_payment_error} ({@code code=card_declined},
 *       {@code decline_code=insufficient_funds} en la regla de los importes terminados en 51).</li>
 *   <li>Capturar lo deja en {@code succeeded}; anular, en {@code canceled}.</li>
 *   <li>Las peticiones inválidas lanzan {@link StripeException}, como el SDK de Stripe:
 *       404 {@code resource_missing} si no existe, 400 {@code payment_intent_unexpected_state}
 *       si el estado no lo admite (capturar dos veces), 400 {@code amount_too_large} si el
 *       reembolso supera lo cobrado y 400 {@code parameter_invalid_integer} si el importe de la
 *       captura no cuadra.</li>
 *   <li>Admite {@code Idempotency-Key} al crear: la misma clave devuelve el mismo PaymentIntent.</li>
 * </ul>
 * Es seguro para hilos.
 */
public final class StripeClient {

    public static final String REQUIRES_CAPTURE = "requires_capture";
    public static final String REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    public static final String SUCCEEDED = "succeeded";
    public static final String CANCELED = "canceled";

    /** {@code last_payment_error} de un PaymentIntent. */
    public record LastPaymentError(String type, String code, String declineCode, String message) {
    }

    /** Lo esencial de un objeto {@code payment_intent}. {@code currency} va en minúsculas, como en Stripe. */
    public record PaymentIntent(String id, long amount, String currency, String status, long amountReceived,
                                String captureMethod, LastPaymentError lastPaymentError) {
    }

    /** Un objeto {@code refund}. */
    public record Refund(String id, String paymentIntent, long amount, String currency, String status) {
    }

    /** Error de la API: lo que el SDK de Stripe lanza con un 4xx o un 5xx. */
    public static final class StripeException extends RuntimeException {

        private final int httpStatus;
        private final String type;
        private final String code;

        public StripeException(int httpStatus, String type, String code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.type = type;
            this.code = code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        /** {@code invalid_request_error}, {@code card_error}, {@code api_error}... */
        public String type() {
            return type;
        }

        public String code() {
            return code;
        }
    }

    private final SimulatedLedger ledger = new SimulatedLedger();
    private final ConcurrentMap<String, PaymentIntent> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Random random;

    public StripeClient(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /** {@code POST /v1/payment_intents} con {@code capture_method=manual&confirm=true}. */
    public PaymentIntent createPaymentIntent(long amount, String currency, String idempotencyKey) {
        if (amount <= 0) {
            throw new StripeException(400, "invalid_request_error", "parameter_invalid_integer",
                    "Invalid positive integer");
        }
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            String id = "pi_" + References.alphanumeric(random, 24);
            if (DeterministicDecline.declines(amount)) {
                return new PaymentIntent(id, amount, currency, REQUIRES_PAYMENT_METHOD, 0, "manual",
                        new LastPaymentError("card_error", "card_declined", "insufficient_funds",
                                "Your card has insufficient funds."));
            }
            ledger.open(id, amount, currency);
            return new PaymentIntent(id, amount, currency, REQUIRES_CAPTURE, 0, "manual", null);
        });
    }

    /** {@code POST /v1/payment_intents/:id/capture} con {@code amount_to_capture}. */
    public PaymentIntent capturePaymentIntent(String id, long amountToCapture) {
        SimulatedLedger.Entry entry = onLedger(() -> ledger.capture(id, amountToCapture, currencyOf(id)));
        return new PaymentIntent(id, entry.amountMinor(), entry.currency(), SUCCEEDED, entry.amountMinor(), "manual",
                null);
    }

    /** {@code POST /v1/refunds} con {@code payment_intent} y {@code amount}. */
    public Refund createRefund(String paymentIntent, long amount, String currency) {
        SimulatedLedger.Entry entry = onLedger(() -> ledger.refund(paymentIntent, amount, currency));
        return new Refund("re_" + References.alphanumeric(random, 24), paymentIntent, amount, entry.currency(),
                SUCCEEDED);
    }

    /** {@code POST /v1/payment_intents/:id/cancel}. */
    public PaymentIntent cancelPaymentIntent(String id) {
        SimulatedLedger.Entry entry = onLedger(() -> ledger.cancel(id));
        return new PaymentIntent(id, entry.amountMinor(), entry.currency(), CANCELED, 0, "manual", null);
    }

    /** El libro, para los tests y para depurar. */
    public SimulatedLedger ledger() {
        return ledger;
    }

    private String currencyOf(String id) {
        return ledger.find(id).map(SimulatedLedger.Entry::currency).orElse("");
    }

    private static SimulatedLedger.Entry onLedger(java.util.function.Supplier<SimulatedLedger.Entry> operation) {
        try {
            return operation.get();
        } catch (SimulatedLedger.RejectedException e) {
            throw switch (e.rejection()) {
                case UNKNOWN_REFERENCE -> new StripeException(404, "invalid_request_error", "resource_missing",
                        "No such payment_intent");
                case INVALID_STATE -> new StripeException(400, "invalid_request_error",
                        "payment_intent_unexpected_state",
                        "This PaymentIntent's status does not allow this operation");
                case AMOUNT_MISMATCH -> new StripeException(400, "invalid_request_error",
                        "parameter_invalid_integer", "The amount does not match the PaymentIntent");
                case REFUND_EXCEEDS_CAPTURED -> new StripeException(400, "invalid_request_error",
                        "amount_too_large", "Refund amount is greater than unrefunded amount");
            };
        }
    }
}
