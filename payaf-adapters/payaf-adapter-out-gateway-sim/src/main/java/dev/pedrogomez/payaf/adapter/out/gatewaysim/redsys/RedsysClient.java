package dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.DeterministicDecline;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.References;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Imitación del TPV virtual de Redsys (la pasarela de los bancos españoles), en su versión REST
 * ({@code trataPeticionREST}). Es el «idioma» de Redsys; {@link RedsysPaymentGateway} lo traduce
 * al puerto.
 * <ul>
 *   <li>Hay un único punto de entrada ({@link #process}) y la operación la decide
 *       {@code Ds_Merchant_TransactionType}: {@code 1} preautorización, {@code 2} confirmación
 *       de la preautorización (la captura), {@code 3} devolución y {@code 9} anulación de la
 *       preautorización.</li>
 *   <li>El pedido lo numera el comercio: {@code Ds_Merchant_Order} tiene 12 caracteres, los 4
 *       primeros numéricos y el resto alfanuméricos. Es la referencia de toda la operación.</li>
 *   <li>Los importes van en céntimos como texto ({@code "10051"}) y la divisa con su código
 *       numérico ISO 4217 ({@code "978"} es el euro).</li>
 *   <li>{@code Ds_Response}: {@code 0000}-{@code 0099} autorizada (con un
 *       {@code Ds_AuthorisationCode} de 6 dígitos), {@code 0900} confirmación o devolución
 *       aceptada, {@code 0400} anulación aceptada, {@code 0190} denegada (la regla de los importes
 *       terminados en 51) y {@code 9xxx} error.</li>
 *   <li>Una petición que no cuadra no lleva {@code Ds_Response} sino un {@code errorCode}
 *       {@code SISxxxx}: {@code SIS0051} pedido repetido, {@code SIS0075} número de pedido con
 *       formato incorrecto, {@code SIS0072} pedido desconocido, {@code SIS0063} operación no
 *       permitida en el estado actual (confirmar dos veces), {@code SIS0062} el importe no cuadra
 *       y {@code SIS0057} la devolución supera lo cobrado. Los códigos están inspirados en los de
 *       Redsys, pero son del simulador.</li>
 * </ul>
 * Es seguro para hilos.
 */
public final class RedsysClient {

    public static final String PREAUTHORIZATION = "1";
    public static final String CONFIRMATION = "2";
    public static final String REFUND = "3";
    public static final String CANCELLATION = "9";

    /** Formato de {@code Ds_Merchant_Order}: 4 dígitos y 8 alfanuméricos. */
    public static final Pattern ORDER_FORMAT = Pattern.compile("[0-9]{4}[A-Za-z0-9]{8}");

    /** Petición: los campos {@code Ds_Merchant_*} que se firman y envían. */
    public record RedsysRequest(String dsMerchantOrder, String dsMerchantAmount, String dsMerchantCurrency,
                                String dsMerchantTransactionType, String dsMerchantMerchantCode,
                                String dsMerchantTerminal) {
    }

    /**
     * Respuesta: los campos {@code Ds_*} de {@code Ds_MerchantParameters}, o un {@code errorCode}
     * {@code SISxxxx} si la petición no se ha podido tratar (y entonces el resto va vacío).
     */
    public record RedsysResponse(String dsOrder, String dsAmount, String dsCurrency, String dsResponse,
                                 String dsAuthorisationCode, String dsTransactionType, String errorCode) {
    }

    private final SimulatedLedger ledger = new SimulatedLedger();
    /** Códigos de autorización usados por pedido, para no repetirlos dentro de un mismo pedido. */
    private final ConcurrentMap<String, Set<String>> authorisationCodes = new ConcurrentHashMap<>();
    private final Random random;

    public RedsysClient(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /** {@code POST /sis/rest/trataPeticionREST}. */
    public RedsysResponse process(RedsysRequest request) {
        String order = request.dsMerchantOrder();
        if (order == null || !ORDER_FORMAT.matcher(order).matches()) {
            return sisError(request, "SIS0075");
        }
        long amount;
        try {
            amount = Long.parseLong(request.dsMerchantAmount());
        } catch (NumberFormatException e) {
            return sisError(request, "SIS0062");
        }
        String currency = request.dsMerchantCurrency();
        String type = String.valueOf(request.dsMerchantTransactionType());
        return switch (type) {
            case PREAUTHORIZATION -> preauthorize(request, order, amount, currency);
            case CONFIRMATION -> onLedger(request, () -> ledger.capture(order, amount, currency), "0900");
            case REFUND -> onLedger(request, () -> ledger.refund(order, amount, currency), "0900");
            case CANCELLATION -> onLedger(request, () -> ledger.cancel(order), "0400");
            default -> sisError(request, "SIS0054");
        };
    }

    public SimulatedLedger ledger() {
        return ledger;
    }

    private RedsysResponse preauthorize(RedsysRequest request, String order, long amount, String currency) {
        if (amount <= 0) {
            return sisError(request, "SIS0062");
        }
        if (DeterministicDecline.declines(amount)) {
            // También queda «usado» el número de pedido: Redsys no deja repetirlo.
            if (authorisationCodes.putIfAbsent(order, ConcurrentHashMap.newKeySet()) != null) {
                return sisError(request, "SIS0051");
            }
            return response(request, "0190", "");
        }
        if (authorisationCodes.putIfAbsent(order, ConcurrentHashMap.newKeySet()) != null) {
            return sisError(request, "SIS0051");
        }
        ledger.open(order, amount, currency);
        return response(request, "0000", newAuthorisationCode(order));
    }

    private RedsysResponse onLedger(RedsysRequest request, Supplier<SimulatedLedger.Entry> operation,
                                    String okResponse) {
        try {
            operation.get();
        } catch (SimulatedLedger.RejectedException e) {
            return sisError(request, switch (e.rejection()) {
                case UNKNOWN_REFERENCE -> "SIS0072";
                case INVALID_STATE -> "SIS0063";
                case AMOUNT_MISMATCH -> "SIS0062";
                case REFUND_EXCEEDS_CAPTURED -> "SIS0057";
            });
        }
        return response(request, okResponse, newAuthorisationCode(request.dsMerchantOrder()));
    }

    /** Un código de autorización de 6 dígitos que no se ha usado antes en el pedido. */
    private String newAuthorisationCode(String order) {
        Set<String> used = authorisationCodes.computeIfAbsent(order, o -> ConcurrentHashMap.newKeySet());
        while (true) {
            String code = References.digits(random, 6, false);
            if (used.add(code)) {
                return code;
            }
        }
    }

    private static RedsysResponse response(RedsysRequest request, String dsResponse, String authorisationCode) {
        return new RedsysResponse(request.dsMerchantOrder(), request.dsMerchantAmount(),
                request.dsMerchantCurrency(), dsResponse, authorisationCode, request.dsMerchantTransactionType(),
                null);
    }

    private static RedsysResponse sisError(RedsysRequest request, String errorCode) {
        return new RedsysResponse(request.dsMerchantOrder(), null, null, null, null,
                request.dsMerchantTransactionType(), errorCode);
    }
}
